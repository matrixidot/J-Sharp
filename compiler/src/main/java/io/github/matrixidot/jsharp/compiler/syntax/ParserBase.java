package io.github.matrixidot.jsharp.compiler.syntax;

import static io.github.matrixidot.jsharp.compiler.syntax.TokenKind.*;

import io.github.matrixidot.jsharp.compiler.ast.Annotation;
import io.github.matrixidot.jsharp.compiler.ast.Arg;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.Modifier;
import io.github.matrixidot.jsharp.compiler.ast.Modifiers;
import io.github.matrixidot.jsharp.compiler.ast.Stmt;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.ast.TypeParam;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticSink;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parser infrastructure: token cursor, error reporting with cascade suppression, side-effect free
 * lookahead scanners, and parsing of types, type parameters, annotations and modifiers.
 *
 * <p>The parser is split into layers ({@code ParserBase <- ExprParser <- StmtParser <- Parser}) to
 * keep each file focused; upward references (e.g. lambda block bodies, anonymous class bodies) go
 * through the abstract hooks declared here.
 */
abstract class ParserBase {
  /** Maximum syntactic nesting before the parser gives up (prevents stack overflow). */
  static final int MAX_DEPTH = 3000; // parser recursion units (about 3 per source nesting level)

  final SourceFile file;
  final List<Token> toks;
  final DiagnosticSink sink;
  int pos;
  private int lastErrorPos = -1;
  int errorCount;
  int depth;

  /**
   * True inside async methods/lambdas and top-level statements, where {@code await} is a keyword.
   */
  boolean asyncContext;

  /** Thrown when nesting exceeds {@link #MAX_DEPTH}; caught by the entry point. */
  static final class TooDeep extends RuntimeException {
    private static final long serialVersionUID = 1L;

    TooDeep() {
      super(null, null, false, false);
    }
  }

  ParserBase(SourceFile file, List<Token> toks, DiagnosticSink sink) {
    this.file = file;
    this.toks = toks;
    this.sink = sink;
  }

  // ------------------------------------------------------------------ hooks

  abstract Expr parseExpr();

  abstract Stmt.Block parseBlock();

  abstract List<Decl> parseClassBody(String className, Decl.TypeKind kind);

  // ------------------------------------------------------------------ cursor

  final Token tok() {
    return toks.get(pos);
  }

  final Token tokAt(int i) {
    return toks.get(Math.min(Math.max(i, 0), toks.size() - 1));
  }

  final TokenKind kind(int i) {
    return tokAt(i).kind();
  }

  final Token peek(int n) {
    return tokAt(pos + n);
  }

  final boolean at(TokenKind k) {
    return tok().kind() == k;
  }

  final boolean atContextual(String word) {
    return tok().isContextual(word);
  }

  final boolean atEof() {
    return tok().kind() == EOF;
  }

  final Token advance() {
    Token t = tok();
    if (t.kind() != EOF) {
      pos++;
    }
    return t;
  }

  final boolean accept(TokenKind k) {
    if (at(k)) {
      advance();
      return true;
    }
    return false;
  }

  final boolean acceptContextual(String word) {
    if (atContextual(word)) {
      advance();
      return true;
    }
    return false;
  }

  /** End offset of the previously consumed token. */
  final int prevEnd() {
    return pos == 0 ? tok().start() : toks.get(pos - 1).end();
  }

  final int startOffset() {
    return tok().start();
  }

  final Span spanFrom(int start) {
    return new Span(start, Math.max(start, prevEnd()));
  }

  /** True if token {@code i+1} immediately follows token {@code i} with no whitespace. */
  final boolean adjacent(int i) {
    return tokAt(i).end() == tokAt(i + 1).start();
  }

  final void enter() {
    if (++depth > MAX_DEPTH) {
      throw new TooDeep();
    }
  }

  final void exit() {
    depth--;
  }

  // ------------------------------------------------------------------ errors

  /** Reports an error unless one was already reported at the current token. */
  final void error(Code code, Span span, String message) {
    errorAt(code, span, message, null);
  }

  final void errorAt(Code code, Span span, String message, String help) {
    errorCount++;
    if (pos == lastErrorPos
        || tok().malformed()
        || tok().is(ERROR)
        || (pos > 0 && toks.get(pos - 1).malformed())) {
      lastErrorPos = pos;
      return;
    }
    lastErrorPos = pos;
    Diagnostic.Builder b = Diagnostic.error(code, file, span, message);
    if (help != null) {
      b.help(help);
    }
    sink.report(b.build());
  }

  final void errorHere(Code code, String message) {
    error(code, tok().span(), message);
  }

  /** Reports an error that is not subject to cascade suppression (e.g. semantic-ish checks). */
  final void errorAlways(Code code, Span span, String message) {
    errorAlways(code, span, message, null);
  }

  final void errorAlways(Code code, Span span, String message, String help) {
    errorCount++;
    sink.report(Diagnostic.error(code, file, span, message).help(help).build());
  }

  /** Consumes a token of kind {@code k} or reports "expected k". Never advances on failure. */
  final Token expect(TokenKind k) {
    if (at(k)) {
      return advance();
    }
    reportExpected("'" + k.text() + "'");
    return new Token(k, prevEnd(), prevEnd(), k.text(), null, false);
  }

  final void reportExpected(String what) {
    Token t = tok();
    // Missing terminators are reported right after the previous token, like rustc/javac.
    boolean terminator = what.equals("';'") || what.equals("')'") || what.equals("']'");
    Span span = terminator && pos > 0 ? new Span(prevEnd(), prevEnd()) : t.span();
    errorAt(Code.EXPECTED_TOKEN, span, "expected " + what + ", found " + t.describe(), null);
  }

  /** Consumes an identifier and returns its name, or reports an error and returns "<error>". */
  final String expectIdent(String what) {
    if (at(IDENTIFIER)) {
      return advance().text();
    }
    if (tok().kind().isKeyword()) {
      Token t = tok();
      errorAt(
          Code.EXPECTED_IDENTIFIER,
          t.span(),
          "expected " + what + ", found " + t.describe(),
          isPrimitiveKind(t.kind())
              ? null
              : "'"
                  + t.text()
                  + "' is a reserved keyword; escape it as `"
                  + t.text()
                  + "` to use it as a name");
      advance();
      return t.text();
    }
    errorAt(
        Code.EXPECTED_IDENTIFIER,
        tok().span(),
        "expected " + what + ", found " + tok().describe(),
        null);
    return "<error>";
  }

  /**
   * Skips tokens until one of {@code stop} (not consumed) or a {@code ;} (consumed) is reached,
   * respecting bracket nesting.
   */
  final void skipUntil(Set<TokenKind> stop, boolean consumeSemi) {
    int nest = 0;
    while (!atEof()) {
      TokenKind k = tok().kind();
      if (nest == 0 && stop.contains(k)) {
        return;
      }
      if (nest == 0 && k == SEMI) {
        if (consumeSemi) {
          advance();
        }
        return;
      }
      if (k == LBRACE || k == LPAREN || k == LBRACKET) {
        nest++;
      } else if (k == RBRACE || k == RPAREN || k == RBRACKET) {
        if (nest == 0) {
          if (k == RBRACE) {
            return;
          }
          advance(); // unmatched ')' or ']': skip it
          continue;
        }
        nest--;
        if (nest == 0 && k == RBRACE) {
          advance();
          return;
        }
      }
      advance();
    }
  }

  // ------------------------------------------------------------------ lookahead scanners
  // Scanners take an absolute token index and return the index just past the construct, or -1.
  // They never report diagnostics and never move the cursor.

  static boolean isPrimitiveKind(TokenKind k) {
    return k.isPrimitiveType() || k == VOID;
  }

  private int scanDepth;

  final int scanType(int i, boolean allowNullable) {
    if (++scanDepth > MAX_DEPTH) {
      scanDepth--;
      return -1;
    }
    try {
      return scanTypeInner(i, allowNullable);
    } finally {
      scanDepth--;
    }
  }

  private int scanTypeInner(int i, boolean allowNullable) {
    i = scanNonArrayType(i, allowNullable);
    if (i < 0) {
      return -1;
    }
    while (true) {
      if (allowNullable && kind(i) == QUESTION) {
        i++;
      } else if (kind(i) == LBRACKET && kind(i + 1) == RBRACKET) {
        i += 2;
      } else {
        return i;
      }
    }
  }

  final int scanNonArrayType(int i, boolean allowNullable) {
    TokenKind k = kind(i);
    if (isPrimitiveKind(k)) {
      return i + 1;
    }
    if (k == LPAREN) {
      int count = 0;
      i++;
      while (true) {
        i = scanType(i, true);
        if (i < 0) {
          return -1;
        }
        if (kind(i) == IDENTIFIER) {
          i++;
        }
        count++;
        if (kind(i) == COMMA) {
          i++;
          continue;
        }
        if (kind(i) == RPAREN && count >= 2) {
          return i + 1;
        }
        return -1;
      }
    }
    if (k != IDENTIFIER) {
      return -1;
    }
    while (true) {
      i++;
      if (kind(i) == LT) {
        i = scanTypeArgs(i);
        if (i < 0) {
          return -1;
        }
      }
      if (kind(i) == DOT && kind(i + 1) == IDENTIFIER) {
        i++;
        continue;
      }
      return i;
    }
  }

  /** Scans {@code <...>} starting at a {@code <} token. */
  final int scanTypeArgs(int i) {
    i++;
    if (kind(i) == GT) {
      return i + 1;
    }
    while (true) {
      if (kind(i) == QUESTION) {
        i++;
      } else {
        Token t = tokAt(i);
        if ((t.isContextual("out") || t.isContextual("in")) && startsType(kind(i + 1))) {
          i++;
        }
        i = scanType(i, true);
        if (i < 0) {
          return -1;
        }
      }
      if (kind(i) == COMMA) {
        i++;
        continue;
      }
      return kind(i) == GT ? i + 1 : -1;
    }
  }

  static boolean startsType(TokenKind k) {
    return k == IDENTIFIER || isPrimitiveKind(k) || k == LPAREN;
  }

  /** Index of the token matching the opening bracket at {@code i}, or -1. */
  final int matching(int i) {
    TokenKind open = kind(i);
    TokenKind close =
        switch (open) {
          case LPAREN -> RPAREN;
          case LBRACKET -> RBRACKET;
          case LBRACE -> RBRACE;
          default -> null;
        };
    if (close == null) {
      return -1;
    }
    int nest = 0;
    for (int j = i; j < toks.size(); j++) {
      TokenKind k = kind(j);
      if (k == LPAREN || k == LBRACKET || k == LBRACE) {
        nest++;
      } else if (k == RPAREN || k == RBRACKET || k == RBRACE) {
        nest--;
        if (nest == 0) {
          return k == close ? j : -1;
        }
      } else if (k == EOF) {
        return -1;
      }
    }
    return -1;
  }

  // ------------------------------------------------------------------ types

  final TypeNode parseType() {
    return parseType(true);
  }

  final TypeNode parseType(boolean allowNullable) {
    enter();
    try {
      int start = startOffset();
      TypeNode t = parseNonArrayType(allowNullable);
      while (true) {
        if (allowNullable && at(QUESTION)) {
          advance();
          t = new TypeNode.Nullable(t, spanFrom(start));
        } else if (at(LBRACKET) && peek(1).is(RBRACKET)) {
          advance();
          advance();
          t = new TypeNode.Array(t, spanFrom(start));
        } else {
          return t;
        }
      }
    } finally {
      exit();
    }
  }

  final TypeNode parseNonArrayType(boolean allowNullable) {
    int start = startOffset();
    Token t = tok();
    if (isPrimitiveKind(t.kind())) {
      advance();
      return new TypeNode.Primitive(primitiveKind(t.kind()), t.span());
    }
    if (t.is(LPAREN)) {
      return parseTupleType();
    }
    if (!t.is(IDENTIFIER)) {
      errorAt(Code.EXPECTED_TYPE, t.span(), "expected type, found " + t.describe(), null);
      if (t.kind().isKeyword() && !t.is(EOF)) {
        advance();
      }
      return errorType(start);
    }
    List<TypeNode.Segment> segs = new ArrayList<>();
    while (true) {
      int segStart = startOffset();
      String name = advance().text();
      List<TypeNode> args = List.of();
      boolean diamond = false;
      if (at(LT)) {
        if (peek(1).is(GT)) {
          advance();
          advance();
          diamond = true;
        } else {
          args = parseTypeArgs();
        }
      }
      segs.add(new TypeNode.Segment(name, args, diamond, spanFrom(segStart)));
      if (at(DOT) && peek(1).is(IDENTIFIER)) {
        advance();
        continue;
      }
      break;
    }
    return new TypeNode.Named(segs, spanFrom(start));
  }

  final TypeNode errorType(int start) {
    return new TypeNode.Named(
        List.of(new TypeNode.Segment("<error>", List.of(), false, new Span(start, start))),
        new Span(start, Math.max(start, prevEnd())));
  }

  static TypeNode.Primitive.Kind primitiveKind(TokenKind k) {
    return switch (k) {
      case BOOLEAN -> TypeNode.Primitive.Kind.BOOLEAN;
      case BYTE -> TypeNode.Primitive.Kind.BYTE;
      case CHAR -> TypeNode.Primitive.Kind.CHAR;
      case SHORT -> TypeNode.Primitive.Kind.SHORT;
      case INT -> TypeNode.Primitive.Kind.INT;
      case LONG -> TypeNode.Primitive.Kind.LONG;
      case FLOAT -> TypeNode.Primitive.Kind.FLOAT;
      case DOUBLE -> TypeNode.Primitive.Kind.DOUBLE;
      case VOID -> TypeNode.Primitive.Kind.VOID;
      default -> throw new IllegalArgumentException(k.toString());
    };
  }

  /** Parses {@code <A, out B, ?>}; the cursor is at {@code <}. */
  final List<TypeNode> parseTypeArgs() {
    expect(LT);
    List<TypeNode> args = new ArrayList<>();
    while (!at(GT) && !atEof()) {
      int start = startOffset();
      int before = pos;
      if (at(QUESTION)) {
        advance();
        args.add(new TypeNode.Wildcard(TypeParam.Variance.INVARIANT, null, spanFrom(start)));
      } else if ((atContextual("out") || atContextual("in")) && startsType(peek(1).kind())) {
        TypeParam.Variance v =
            advance().text().equals("out") ? TypeParam.Variance.OUT : TypeParam.Variance.IN;
        TypeNode bound = parseType();
        args.add(new TypeNode.Wildcard(v, bound, spanFrom(start)));
      } else {
        args.add(parseType());
      }
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(GT);
    return args;
  }

  final TypeNode parseTupleType() {
    int start = startOffset();
    expect(LPAREN);
    List<TypeNode.Tuple.Element> elems = new ArrayList<>();
    while (!at(RPAREN) && !atEof()) {
      int es = startOffset();
      int before = pos;
      TypeNode t = parseType();
      String name = null;
      if (at(IDENTIFIER)) {
        name = advance().text();
      }
      elems.add(new TypeNode.Tuple.Element(t, name, spanFrom(es)));
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RPAREN);
    Span span = spanFrom(start);
    if (elems.size() < 2) {
      errorAlways(Code.INVALID_TUPLE, span, "a tuple type needs at least two elements");
    }
    return new TypeNode.Tuple(elems, span);
  }

  /** Parses {@code <in T, out U : Bound & Other>}; the cursor is at {@code <}. */
  final List<TypeParam> parseTypeParams() {
    expect(LT);
    List<TypeParam> out = new ArrayList<>();
    while (!at(GT) && !atEof()) {
      int start = startOffset();
      int before = pos;
      TypeParam.Variance v = TypeParam.Variance.INVARIANT;
      if ((atContextual("in") || atContextual("out")) && peek(1).is(IDENTIFIER)) {
        v = advance().text().equals("in") ? TypeParam.Variance.IN : TypeParam.Variance.OUT;
      }
      String name = expectIdent("type parameter name");
      List<TypeNode> bounds = new ArrayList<>();
      if (accept(COLON)) {
        bounds.add(parseType());
        while (accept(AMP)) {
          bounds.add(parseType());
        }
      }
      out.add(new TypeParam(v, name, bounds, spanFrom(start)));
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(GT);
    return out;
  }

  /** Parses {@code where T : A, B where U : C} clauses and merges them into {@code params}. */
  final List<TypeParam> parseWhereClauses(List<TypeParam> params) {
    if (!atContextual("where")) {
      return params;
    }
    List<TypeParam> merged = new ArrayList<>(params);
    while (atContextual("where") && peek(1).is(IDENTIFIER)) {
      advance();
      Token nameTok = advance();
      expect(COLON);
      List<TypeNode> bounds = new ArrayList<>();
      do {
        bounds.add(parseType());
      } while (accept(COMMA));
      int idx = -1;
      for (int i = 0; i < merged.size(); i++) {
        if (merged.get(i).name().equals(nameTok.text())) {
          idx = i;
        }
      }
      if (idx < 0) {
        errorAlways(
            Code.EXPECTED_IDENTIFIER,
            nameTok.span(),
            "'" + nameTok.text() + "' is not a type parameter of this declaration");
        continue;
      }
      TypeParam p = merged.get(idx);
      List<TypeNode> all = new ArrayList<>(p.bounds());
      all.addAll(bounds);
      merged.set(idx, new TypeParam(p.variance(), p.name(), all, p.span()));
    }
    return merged;
  }

  // ------------------------------------------------------------------ annotations & modifiers

  /** Parses one annotation; cursor at {@code @}. */
  final Annotation parseAnnotation() {
    int start = startOffset();
    expect(AT);
    String target = null;
    if (at(IDENTIFIER) && peek(1).is(COLON) && !peek(2).is(COLON)) {
      target = advance().text();
      advance();
    }
    TypeNode name;
    if (at(IDENTIFIER)) {
      name = parseNonArrayType(false);
    } else {
      reportExpected("annotation name");
      name = errorType(startOffset());
    }
    List<Arg> args = new ArrayList<>();
    if (at(LPAREN)) {
      advance();
      while (!at(RPAREN) && !atEof()) {
        int as = startOffset();
        int before = pos;
        String argName = null;
        if (at(IDENTIFIER) && peek(1).is(EQ)) {
          argName = advance().text();
          advance();
        }
        Expr value = at(LBRACE) ? parseArrayInit() : parseExpr();
        args.add(new Arg(argName, value, spanFrom(as)));
        if (!accept(COMMA) || pos == before) {
          break;
        }
      }
      expect(RPAREN);
    }
    return new Annotation(target, name, args, spanFrom(start));
  }

  abstract Expr.ArrayInit parseArrayInit();

  private static final Set<String> CONTEXTUAL_MODIFIERS =
      Set.of("base", "open", "sealed", "override", "async", "required");

  /** True if a contextual modifier word at index {@code i} acts as a modifier. */
  final boolean isContextualModifierAt(int i) {
    Token t = tokAt(i);
    if (t.kind() != IDENTIFIER || t.escaped() || !CONTEXTUAL_MODIFIERS.contains(t.text())) {
      return false;
    }
    TokenKind next = kind(i + 1);
    return next == IDENTIFIER
        || isPrimitiveKind(next)
        || isModifierKeyword(next)
        || next == CLASS
        || next == INTERFACE
        || next == ENUM
        || next == AT
        || (next == DEFAULT && startsType(kind(i + 2))) // override default String f()
        || (next == LPAREN && t.text().equals("async") == false && scanType(i + 1, true) > 0);
  }

  static boolean isModifierKeyword(TokenKind k) {
    return switch (k) {
      case PUBLIC,
          PROTECTED,
          PRIVATE,
          INTERNAL,
          STATIC,
          FINAL,
          ABSTRACT,
          SYNCHRONIZED,
          VOLATILE,
          TRANSIENT,
          NATIVE ->
          true;
      default -> false;
    };
  }

  /** True if the token at {@code i} starts a modifier or annotation. */
  final boolean isModifierStart(int i) {
    TokenKind k = kind(i);
    return isModifierKeyword(k)
        || k == AT
        || isContextualModifierAt(i)
        || (k == DEFAULT && startsType(kind(i + 1)));
  }

  /** Index after a run of modifiers/annotations starting at {@code i}. */
  final int skipModifiers(int i) {
    while (true) {
      if (kind(i) == AT) {
        i++;
        if (kind(i) == IDENTIFIER && kind(i + 1) == COLON) {
          i += 2;
        }
        int t = scanNonArrayType(i, false);
        if (t < 0) {
          return i;
        }
        i = t;
        if (kind(i) == LPAREN) {
          int m = matching(i);
          if (m < 0) {
            return i;
          }
          i = m + 1;
        }
      } else if (isModifierStart(i)) {
        i++;
      } else {
        return i;
      }
    }
  }

  /** Parses modifiers and annotations. */
  final Modifiers parseModifiers() {
    int start = startOffset();
    List<Modifiers.Item> items = new ArrayList<>();
    List<Annotation> anns = new ArrayList<>();
    while (true) {
      Token t = tok();
      Modifier m = null;
      if (t.is(AT)) {
        anns.add(parseAnnotation());
        continue;
      }
      if (isModifierKeyword(t.kind())) {
        m = Modifier.valueOf(t.kind().name());
      } else if (t.is(DEFAULT) && startsType(peek(1).kind())) {
        m = Modifier.DEFAULT;
      } else if (isContextualModifierAt(pos) && t.text().equals("open")) {
        // `open` (Kotlin's spelling) was renamed to `base` (D075).
        errorAlways(
            Code.INVALID_MODIFIER,
            t.span(),
            "J# uses 'base' instead of 'open'",
            "write 'base' to allow inheriting from a class or overriding a member");
        m = Modifier.BASE;
      } else if (isContextualModifierAt(pos)) {
        m = Modifier.valueOf(t.text().toUpperCase(java.util.Locale.ROOT));
      } else if (t.isContextual("value") && peek(1).is(CLASS)) {
        advance();
        errorAlways(
            Code.UNSUPPORTED_SYNTAX,
            t.span(),
            "'value class' is reserved for a future version (Valhalla value types)");
        continue;
      }
      if (m == null) {
        break;
      }
      advance();
      for (Modifiers.Item existing : items) {
        if (existing.modifier() == m) {
          errorAlways(
              Code.DUPLICATE_MODIFIER, t.span(), "duplicate modifier '" + m.keyword() + "'");
        }
      }
      items.add(new Modifiers.Item(m, t.span()));
    }
    if (items.isEmpty() && anns.isEmpty()) {
      return Modifiers.empty(start);
    }
    return new Modifiers(items, anns, spanFrom(start));
  }
}
