package io.github.matrixidot.jsharp.compiler.syntax;

import static io.github.matrixidot.jsharp.compiler.syntax.TokenKind.*;

import io.github.matrixidot.jsharp.compiler.ast.CatchClause;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.DeconstructVar;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.LocalKind;
import io.github.matrixidot.jsharp.compiler.ast.Modifier;
import io.github.matrixidot.jsharp.compiler.ast.Modifiers;
import io.github.matrixidot.jsharp.compiler.ast.Pattern;
import io.github.matrixidot.jsharp.compiler.ast.Stmt;
import io.github.matrixidot.jsharp.compiler.ast.SwitchSection;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.ast.TypeParam;
import io.github.matrixidot.jsharp.compiler.ast.VarDeclarator;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticSink;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Statement parsing with statement-level error recovery. */
abstract class StmtParser extends ExprParser {
  StmtParser(SourceFile file, List<Token> toks, DiagnosticSink sink) {
    super(file, toks, sink);
  }

  /** Parses a local (or nested) type declaration with already-parsed modifiers. */
  abstract Decl.TypeDecl parseTypeDecl(Modifiers mods);

  /** {@code R name(params) body} in a block (D083). */
  abstract Decl.Method parseLocalFunction(int start, List<TypeParam> typeParams);

  private static final Set<TokenKind> STATEMENT_KEYWORDS =
      Set.of(
          IF, WHILE, DO, FOR, FOREACH, RETURN, THROW, TRY, SWITCH, BREAK, CONTINUE, USING, RBRACE,
          LBRACE);

  @Override
  final Stmt.Block parseBlock() {
    int start = startOffset();
    expect(LBRACE);
    List<Stmt> stmts = new ArrayList<>();
    while (!at(RBRACE) && !atEof()) {
      stmts.add(parseStatementRecovering());
    }
    expect(RBRACE);
    return new Stmt.Block(stmts, spanFrom(start));
  }

  /** Parses one statement; on error, skips to a plausible statement boundary. */
  final Stmt parseStatementRecovering() {
    int before = pos;
    int errs = errorCount;
    Stmt s = parseStatement();
    if (errorCount > errs) {
      synchronizeStatement();
    }
    if (pos == before) {
      advance();
    }
    return s;
  }

  private void synchronizeStatement() {
    if (pos > 0 && (toks.get(pos - 1).is(SEMI) || toks.get(pos - 1).is(RBRACE))) {
      return;
    }
    while (!atEof()) {
      TokenKind k = tok().kind();
      if (k == SEMI) {
        advance();
        return;
      }
      if (STATEMENT_KEYWORDS.contains(k)) {
        return;
      }
      if ((atContextual("var") || atContextual("val")) && peek(1).is(IDENTIFIER)) {
        return;
      }
      if (k == LPAREN || k == LBRACKET) {
        int m = matching(pos);
        if (m > 0) {
          pos = m + 1;
          continue;
        }
      }
      advance();
    }
  }

  final Stmt parseStatement() {
    enter();
    try {
      return parseStatementInner();
    } finally {
      exit();
    }
  }

  private Stmt parseStatementInner() {
    int start = startOffset();
    Token t = tok();
    switch (t.kind()) {
      case LBRACE:
        return parseBlock();
      case SEMI:
        advance();
        return new Stmt.Empty(t.span());
      case IF:
        return parseIf();
      case WHILE:
        {
          advance();
          Expr cond = parseParenCondition();
          Stmt body = parseEmbedded();
          return new Stmt.While(cond, body, spanFrom(start));
        }
      case DO:
        {
          advance();
          Stmt body = parseEmbedded();
          expect(WHILE);
          Expr cond = parseParenCondition();
          expect(SEMI);
          return new Stmt.DoWhile(body, cond, spanFrom(start));
        }
      case FOR:
        return parseFor();
      case FOREACH:
        return parseForeach();
      case BREAK:
      case CONTINUE:
        {
          advance();
          String label = at(IDENTIFIER) ? advance().text() : null;
          expect(SEMI);
          return t.is(BREAK)
              ? new Stmt.Break(label, spanFrom(start))
              : new Stmt.Continue(label, spanFrom(start));
        }
      case RETURN:
        {
          advance();
          Expr value = at(SEMI) ? null : parseExpr();
          expect(SEMI);
          return new Stmt.Return(value, spanFrom(start));
        }
      case THROW:
        {
          advance();
          Expr value = at(SEMI) ? null : parseExpr(); // `throw;` rethrows inside catch
          expect(SEMI);
          return new Stmt.Throw(value, spanFrom(start));
        }
      case TRY:
        return parseTry();
      case SWITCH:
        return parseSwitchStatement();
      case USING:
        return parseUsing();
      case CLASS:
      case INTERFACE:
      case ENUM:
        return new Stmt.LocalType(parseTypeDecl(Modifiers.empty(start)), spanFrom(start));
      case ELSE:
        {
          advance();
          errorAlways(Code.UNEXPECTED_TOKEN, t.span(), "'else' without a matching 'if'");
          return new Stmt.Empty(t.span());
        }
      case CASE:
      case DEFAULT:
        {
          errorAt(
              Code.UNEXPECTED_TOKEN,
              t.span(),
              "'" + t.text() + "' label outside of a switch statement",
              null);
          advance();
          return new Stmt.Empty(t.span());
        }
      default:
        break;
    }
    if (t.is(IDENTIFIER)) {
      if (t.isContextual("lock") && peek(1).is(LPAREN)) {
        int m = matching(pos + 1);
        if (m > 0 && kind(m + 1) == LBRACE) {
          advance();
          Expr monitor = parseParenCondition();
          Stmt.Block body = parseBlock();
          return new Stmt.Lock(monitor, body, spanFrom(start));
        }
      }
      if (t.isContextual("yield") && startsYieldValue(peek(1).kind())) {
        advance();
        Expr value = parseExpr();
        expect(SEMI);
        return new Stmt.Yield(value, spanFrom(start));
      }
      if (t.isContextual("checked") && peek(1).is(LBRACE)) {
        advance();
        Stmt.Block body = parseBlock();
        return new Stmt.Checked(body, spanFrom(start));
      }
      if (peek(1).is(COLON) && !t.isContextual("default")) {
        advance();
        advance();
        Stmt body = parseStatement();
        return new Stmt.Labeled(t.text(), t.span(), body, spanFrom(start));
      }
      if (t.isContextual("record")
          && peek(1).is(IDENTIFIER)
          && (peek(2).is(LPAREN) || peek(2).is(LT))) {
        return new Stmt.LocalType(parseTypeDecl(Modifiers.empty(start)), spanFrom(start));
      }
    }
    // Modifiers/annotations: local type declaration or a local variable with modifiers.
    if (isModifierStart(pos)) {
      int after = skipModifiers(pos);
      TokenKind k = kind(after);
      if (k == CLASS || k == INTERFACE || k == ENUM || tokAt(after).isContextual("record")) {
        Modifiers mods = parseModifiers();
        return new Stmt.LocalType(parseTypeDecl(mods), spanFrom(start));
      }
      Modifiers mods = parseModifiers();
      for (Modifiers.Item item : mods.list()) {
        if (item.modifier() != Modifier.FINAL && item.modifier() != Modifier.ATOMIC) {
          errorAlways(
              Code.UNEXPECTED_TOKEN,
              item.span(),
              "modifier '" + item.modifier().keyword() + "' is not allowed on a local variable");
        }
      }
      Stmt decl = parseLocalDeclaration(mods, start, true);
      expect(SEMI);
      return decl;
    }
    if (isLocalDeclarationAhead()) {
      Stmt decl = parseLocalDeclaration(Modifiers.empty(start), start, true);
      expect(SEMI);
      return decl;
    }
    if (at(LT)) {
      // `<T> T id(T x) => x;`: no statement starts with '<' otherwise.
      List<TypeParam> tps = parseTypeParams();
      Decl.Method m = parseLocalFunction(start, tps);
      return new Stmt.LocalFunction(m, m.span());
    }
    if (isLocalFunctionAhead()) {
      Decl.Method m = parseLocalFunction(start, List.of());
      return new Stmt.LocalFunction(m, m.span());
    }
    Expr e = parseExpr();
    if (e instanceof Expr.Error && !at(SEMI)) {
      return new Stmt.ExprStmt(e, spanFrom(start));
    }
    expect(SEMI);
    return new Stmt.ExprStmt(e, spanFrom(start));
  }

  /** {@code Type name(} or {@code Type name<T>(} at statement level. */
  private boolean isLocalFunctionAhead() {
    int j = scanType(pos, true);
    if (j < 0 || kind(j) != IDENTIFIER) {
      return false;
    }
    if (kind(j + 1) == LPAREN) {
      int m = matching(j + 1);
      return m > 0 && (kind(m + 1) == LBRACE || kind(m + 1) == ARROW);
    }
    return false;
  }

  /** After {@code yield}: a value, not the use of a variable or method named yield. */
  private static boolean startsYieldValue(TokenKind next) {
    return switch (next) {
      case EQ,
          DOT,
          LBRACKET,
          SEMI,
          PLUSPLUS,
          MINUSMINUS,
          PLUS_EQ,
          MINUS_EQ,
          STAR_EQ,
          SLASH_EQ,
          PERCENT_EQ,
          AMP_EQ,
          BAR_EQ,
          CARET_EQ,
          QUESTION_DOT,
          QUESTION_QUESTION_EQ,
          ARROW,
          EOF ->
          false;
      default -> true;
    };
  }

  /** True if a local variable declaration (or deconstruction) starts at the cursor. */
  final boolean isLocalDeclarationAhead() {
    Token t = tok();
    if ((t.isContextual("var") || t.isContextual("val"))
        && (peek(1).is(IDENTIFIER) || peek(1).is(LPAREN))) {
      if (peek(1).is(LPAREN)) {
        // `var (a, b) = ...` vs. a call of a method named var.
        int m = matching(pos + 1);
        return m > 0 && kind(m + 1) == EQ;
      }
      return true;
    }
    if ((t.isContextual("var") || t.isContextual("val"))
        && peek(1).kind().isKeyword()
        && peek(2).is(EQ)) {
      return true; // `var if = 1;`: report the keyword as a bad variable name
    }
    if (t.isContextual("await") && peek(1).is(IDENTIFIER)) {
      return false; // `await task;` is an await expression, never a variable of type `await`
    }
    int j = scanType(pos, true);
    if (j < 0) {
      return false;
    }
    if (kind(j) == IDENTIFIER) {
      TokenKind after = kind(j + 1);
      return after == EQ || after == SEMI || after == COMMA || tokAt(j + 1).isContextual("in");
    }
    // `(int a, String b) = t;` — a tuple type whose elements are all named.
    if (t.is(LPAREN) && kind(j) == EQ) {
      return allTupleElementsNamed(pos);
    }
    return false;
  }

  private boolean allTupleElementsNamed(int i) {
    int m = matching(i);
    if (m < 0) {
      return false;
    }
    int k = i + 1;
    while (k < m) {
      int e = scanType(k, true);
      if (e < 0 || kind(e) != IDENTIFIER) {
        return false;
      }
      k = e + 1;
      if (kind(k) == COMMA) {
        k++;
      }
    }
    return true;
  }

  /**
   * Parses a local declaration without the trailing semicolon: {@code var x = 1}, {@code int a, b =
   * 2}, {@code var (x, y) = p}, {@code (int a, int b) = t}.
   */
  final Stmt parseLocalDeclaration(Modifiers mods, int start, boolean allowMultiple) {
    LocalKind kind = LocalKind.TYPED;
    TypeNode type = null;
    if ((atContextual("var") || atContextual("val"))
        && (peek(1).is(IDENTIFIER) || peek(1).is(LPAREN) || peek(1).kind().isKeyword())) {
      kind = advance().text().equals("var") ? LocalKind.VAR : LocalKind.VAL;
      if (at(LPAREN)) {
        List<DeconstructVar> vars = parseDeconstructTargets(false);
        expect(EQ);
        Expr init = parseExpr();
        return new Stmt.Deconstruct(kind, vars, init, spanFrom(start));
      }
    } else if (mods.has(Modifier.FINAL) && (atContextual("var") || atContextual("val"))) {
      kind = advance().text().equals("var") ? LocalKind.VAR : LocalKind.VAL;
    } else if (at(LPAREN) && allTupleElementsNamed(pos) && kind(matching(pos) + 1) == EQ) {
      List<DeconstructVar> vars = parseDeconstructTargets(true);
      expect(EQ);
      Expr init = parseExpr();
      return new Stmt.Deconstruct(LocalKind.TYPED, vars, init, spanFrom(start));
    } else {
      type = parseType();
    }
    if (mods.has(Modifier.FINAL) && kind == LocalKind.VAR) {
      kind = LocalKind.VAL;
    }
    List<VarDeclarator> vars = new ArrayList<>();
    do {
      if (!vars.isEmpty()) {
        typedDeclaratorAhead(
            type == null ? null : file.text(type.span()),
            vars.stream().map(VarDeclarator::name).toList());
      }
      int ds = startOffset();
      Span nameSpan = tok().span();
      String name = expectIdent("variable name");
      Expr init = null;
      if (accept(EQ)) {
        init = at(LBRACE) ? parseBraceLiteral() : parseExpr();
      }
      vars.add(new VarDeclarator(name, nameSpan, init, spanFrom(ds)));
    } while (allowMultiple && accept(COMMA));
    return new Stmt.LocalVar(mods, kind, type, vars, spanFrom(start));
  }

  /** Parses {@code (a, (b, c), _)} or, if {@code typed}, {@code (int a, String b)}. */
  final List<DeconstructVar> parseDeconstructTargets(boolean typed) {
    expect(LPAREN);
    List<DeconstructVar> out = new ArrayList<>();
    while (!at(RPAREN) && !atEof()) {
      int start = startOffset();
      int before = pos;
      if (at(LPAREN) && !typed) {
        List<DeconstructVar> nested = parseDeconstructTargets(false);
        out.add(new DeconstructVar(null, null, null, nested, spanFrom(start)));
      } else {
        TypeNode type = null;
        if (typed
            || (at(IDENTIFIER)
                && scanType(pos, true) > 0
                && kind(scanType(pos, true)) == IDENTIFIER)) {
          type = parseType();
        }
        Span nameSpan = tok().span();
        String name = expectIdent("variable name");
        out.add(new DeconstructVar(type, name, nameSpan, null, spanFrom(start)));
      }
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RPAREN);
    if (out.size() < 2) {
      errorAlways(
          Code.INVALID_TUPLE, spanFrom(tok().start()), "deconstruction needs at least two targets");
    }
    return out;
  }

  /** A statement in an embedded position (if/while/for bodies). Declarations are not allowed. */
  private Stmt parseEmbedded() {
    int start = startOffset();
    if (isLocalDeclarationAhead()) {
      errorAt(
          Code.EXPECTED_STATEMENT,
          tok().span(),
          "a variable declaration cannot be the body of a control statement",
          "wrap it in braces { ... }");
      Stmt s = parseLocalDeclaration(Modifiers.empty(start), start, true);
      expect(SEMI);
      return s;
    }
    return parseStatement();
  }

  private Expr parseParenCondition() {
    expect(LPAREN);
    Expr e = parseExpr();
    expect(RPAREN);
    return e;
  }

  private Stmt parseIf() {
    int start = startOffset();
    expect(IF);
    Expr cond = parseParenCondition();
    Stmt then = parseEmbedded();
    Stmt otherwise = null;
    if (accept(ELSE)) {
      otherwise = parseEmbedded();
    }
    return new Stmt.If(cond, then, otherwise, spanFrom(start));
  }

  private Stmt parseFor() {
    int start = startOffset();
    expect(FOR);
    expect(LPAREN);
    // Java-style enhanced for: give a precise hint instead of a cascade.
    int j = at(IDENTIFIER) || isPrimitiveKind(tok().kind()) ? scanType(pos, true) : -1;
    if (j > 0
        && kind(j) == IDENTIFIER
        && (kind(j + 1) == COLON || tokAt(j + 1).isContextual("in"))) {
      String variable = file.text(new Span(tok().span().start(), tokAt(j).span().end()));
      errorAlways(
          Code.UNEXPECTED_TOKEN,
          tokAt(pos - 2).span(),
          "enhanced for loops are written with 'foreach'",
          "write 'foreach (" + variable + " in ...)'");
      pos--; // re-parse as foreach starting at '('
      return parseForeachRest(start, true);
    }
    List<Stmt> init = new ArrayList<>();
    if (!at(SEMI)) {
      int is = startOffset();
      if (isLocalDeclarationAhead() || isModifierStart(pos)) {
        Modifiers mods = parseModifiers();
        init.add(parseLocalDeclaration(mods, is, true));
      } else {
        do {
          int es = startOffset();
          Expr e = parseExpr();
          init.add(new Stmt.ExprStmt(e, spanFrom(es)));
        } while (accept(COMMA));
      }
    }
    expect(SEMI);
    Expr cond = at(SEMI) ? null : parseExpr();
    expect(SEMI);
    List<Expr> update = new ArrayList<>();
    if (!at(RPAREN)) {
      do {
        update.add(parseExpr());
      } while (accept(COMMA));
    }
    expect(RPAREN);
    Stmt body = parseEmbedded();
    return new Stmt.For(init, cond, update, body, spanFrom(start));
  }

  private Stmt parseForeach() {
    int start = startOffset();
    expect(FOREACH);
    return parseForeachRest(start, false);
  }

  private Stmt parseForeachRest(int start, boolean javaStyle) {
    expect(LPAREN);
    LocalKind kind = LocalKind.TYPED;
    TypeNode type = null;
    String name = null;
    Span nameSpan = null;
    List<DeconstructVar> decon = null;
    if ((atContextual("var") || atContextual("val"))
        && (peek(1).is(IDENTIFIER) || peek(1).is(LPAREN))) {
      kind = advance().text().equals("var") ? LocalKind.VAR : LocalKind.VAL;
      if (at(LPAREN)) {
        decon = parseDeconstructTargets(false);
      }
    } else if (at(LPAREN) && allTupleElementsNamed(pos)) {
      decon = parseDeconstructTargets(true);
    } else {
      type = parseType();
    }
    if (decon == null) {
      nameSpan = tok().span();
      name = expectIdent("loop variable name");
    }
    if (!acceptContextual("in")) {
      if (at(COLON)) {
        if (!javaStyle) {
          errorAt(
              Code.UNEXPECTED_TOKEN,
              tok().span(),
              "expected 'in', found ':'",
              "write 'foreach (Type x in items)'");
        }
        advance();
      } else {
        reportExpected("'in'");
      }
    }
    Expr iterable = parseExpr();
    expect(RPAREN);
    Stmt body = parseEmbedded();
    return new Stmt.Foreach(kind, type, name, nameSpan, decon, iterable, body, spanFrom(start));
  }

  private Stmt parseTry() {
    int start = startOffset();
    Span tryKeyword = tok().span();
    expect(TRY);
    if (at(LPAREN)) {
      errorAt(
          Code.UNEXPECTED_TOKEN,
          tok().span(),
          "try-with-resources is written with 'using'",
          "write 'using (var r = open()) { ... }' or 'using var r = open();'");
      int m = matching(pos);
      if (m > 0) {
        pos = m + 1;
      }
    }
    Stmt.Block body = parseBlock();
    List<CatchClause> catches = new ArrayList<>();
    while (at(CATCH)) {
      int cs = startOffset();
      advance();
      List<TypeNode> types = new ArrayList<>();
      String name = null;
      Span nameSpan = null;
      if (accept(LPAREN)) {
        types.add(parseType(false));
        while (accept(BAR)) {
          types.add(parseType(false));
        }
        if (at(IDENTIFIER)) {
          nameSpan = tok().span();
          name = advance().text();
        }
        expect(RPAREN);
      }
      Expr filter = null;
      if (acceptContextual("when")) {
        filter = parseParenCondition();
      }
      Stmt.Block cbody = parseBlock();
      catches.add(new CatchClause(types, name, nameSpan, filter, cbody, spanFrom(cs)));
    }
    Stmt.Block fin = null;
    if (accept(FINALLY)) {
      fin = parseBlock();
    }
    if (catches.isEmpty() && fin == null) {
      errorAlways(
          Code.EXPECTED_TOKEN, tryKeyword, "a try statement needs at least one catch or finally");
    }
    return new Stmt.Try(body, catches, fin, spanFrom(start));
  }

  private Stmt parseUsing() {
    int start = startOffset();
    expect(USING);
    if (at(LPAREN)) {
      advance();
      int rs = startOffset();
      Stmt resource;
      if (isLocalDeclarationAhead() || isModifierStart(pos)) {
        Modifiers mods = parseModifiers();
        resource = parseLocalDeclaration(mods, rs, true);
      } else {
        Expr e = parseExpr();
        resource = new Stmt.ExprStmt(e, spanFrom(rs));
      }
      expect(RPAREN);
      Stmt body = parseEmbedded();
      return new Stmt.Using(resource, body, spanFrom(start));
    }
    int ds = startOffset();
    Modifiers mods = parseModifiers();
    Stmt decl = parseLocalDeclaration(mods, ds, true);
    expect(SEMI);
    if (decl instanceof Stmt.LocalVar lv) {
      return new Stmt.UsingDecl(lv, spanFrom(start));
    }
    errorAlways(Code.UNEXPECTED_TOKEN, decl.span(), "a using declaration cannot deconstruct");
    return decl;
  }

  private Stmt parseSwitchStatement() {
    int start = startOffset();
    expect(SWITCH);
    Expr selector = parseParenCondition();
    expect(LBRACE);
    List<SwitchSection> sections = new ArrayList<>();
    while (!at(RBRACE) && !atEof()) {
      int ss = startOffset();
      List<SwitchSection.Label> labels = new ArrayList<>();
      while (at(CASE) || (at(DEFAULT) && peek(1).is(COLON))) {
        int ls = startOffset();
        if (accept(DEFAULT)) {
          expect(COLON);
          labels.add(new SwitchSection.Label(null, null, spanFrom(ls)));
          continue;
        }
        advance();
        Pattern p = parsePattern();
        Expr guard = acceptContextual("when") ? parseExpr() : null;
        if (at(ARROW)) {
          errorAt(
              Code.UNEXPECTED_TOKEN,
              tok().span(),
              "switch statement labels end with ':'",
              "use 'case X:' in statements, or a switch expression 'x switch { X => ... }'");
          advance();
        } else {
          expect(COLON);
        }
        labels.add(new SwitchSection.Label(p, guard, spanFrom(ls)));
      }
      if (labels.isEmpty()) {
        errorAt(
            Code.EXPECTED_TOKEN,
            tok().span(),
            "expected 'case' or 'default', found " + tok().describe(),
            null);
        skipUntil(Set.of(CASE, DEFAULT, RBRACE), true);
        continue;
      }
      List<Stmt> body = new ArrayList<>();
      while (!at(CASE) && !(at(DEFAULT) && peek(1).is(COLON)) && !at(RBRACE) && !atEof()) {
        body.add(parseStatementRecovering());
      }
      sections.add(new SwitchSection(labels, body, spanFrom(ss)));
    }
    expect(RBRACE);
    return new Stmt.Switch(selector, sections, spanFrom(start));
  }
}
