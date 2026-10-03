package io.github.matrixidot.jsharp.compiler.syntax;

import static io.github.matrixidot.jsharp.compiler.syntax.TokenKind.*;

import io.github.matrixidot.jsharp.compiler.ast.Accessor;
import io.github.matrixidot.jsharp.compiler.ast.Annotation;
import io.github.matrixidot.jsharp.compiler.ast.Arg;
import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.EnumConstant;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.ImportDecl;
import io.github.matrixidot.jsharp.compiler.ast.LocalKind;
import io.github.matrixidot.jsharp.compiler.ast.Modifier;
import io.github.matrixidot.jsharp.compiler.ast.Modifiers;
import io.github.matrixidot.jsharp.compiler.ast.PackageDecl;
import io.github.matrixidot.jsharp.compiler.ast.Param;
import io.github.matrixidot.jsharp.compiler.ast.Stmt;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.ast.TypeParam;
import io.github.matrixidot.jsharp.compiler.ast.VarDeclarator;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticSink;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The J# parser: recursive descent for declarations and statements, precedence climbing for
 * expressions. Never throws on malformed input; reports all syntax errors it can find, recovering
 * at statement and member boundaries.
 */
public final class Parser extends StmtParser {

  private Parser(SourceFile file, List<Token> toks, DiagnosticSink sink) {
    super(file, toks, sink);
  }

  /** Lexes and parses {@code file}, reporting problems to {@code sink}. */
  public static CompilationUnit parse(SourceFile file, DiagnosticSink sink) {
    List<Token> toks = Lexer.tokenize(file, sink);
    Parser p = new Parser(file, toks, sink);
    try {
      return p.parseCompilationUnit();
    } catch (TooDeep | StackOverflowError e) {
      sink.report(
          Diagnostic.error(
                  Code.UNSUPPORTED_SYNTAX, file, p.tok().span(), "code is nested too deeply")
              .help("split deeply nested expressions or blocks using local variables or functions")
              .build());
      return new CompilationUnit(file, List.of(), null, List.of(), List.of(), new Span(0, 0));
    }
  }

  /** Parses a single expression (for tests and tools). */
  public static Expr parseExpression(SourceFile file, DiagnosticSink sink) {
    Parser p = new Parser(file, Lexer.tokenize(file, sink), sink);
    try {
      Expr e = p.parseExpr();
      if (!p.atEof()) {
        p.reportExpected("end of expression");
      }
      return e;
    } catch (TooDeep | StackOverflowError e) {
      sink.report(
          Diagnostic.error(
                  Code.UNSUPPORTED_SYNTAX, file, p.tok().span(), "expression is nested too deeply")
              .build());
      return new Expr.Error(new Span(0, 0));
    }
  }

  @Override
  ExprParser subParser(List<Token> tokens) {
    return new Parser(file, tokens, sink);
  }

  // ------------------------------------------------------------------ compilation unit

  private CompilationUnit parseCompilationUnit() {
    List<Annotation> fileAnns = new ArrayList<>();
    // `@file:...` annotations, and annotations directly preceding `package`.
    while (at(AT)) {
      int after = skipModifiers(pos);
      boolean fileTarget = peek(1).isContextual("file") && peek(2).is(COLON);
      if (!fileTarget && kind(after) != PACKAGE) {
        break;
      }
      Annotation a = parseAnnotation();
      fileAnns.add(a);
    }
    PackageDecl pkg = null;
    if (at(PACKAGE)) {
      pkg = parsePackage();
    }
    List<ImportDecl> imports = new ArrayList<>();
    while (at(IMPORT)) {
      imports.add(parseImport());
    }
    List<Decl> members = new ArrayList<>();
    boolean savedAsync = asyncContext;
    while (!atEof()) {
      int before = pos;
      int errs = errorCount;
      if (at(PACKAGE)) {
        Token t = tok();
        PackageDecl p = parsePackage();
        errorAlways(
            Code.MISPLACED_PACKAGE,
            p.span(),
            pkg == null
                ? "package declaration must be the first declaration in the file"
                : "duplicate package declaration");
        if (pkg == null) {
          pkg = p;
        }
        continue;
      } else if (at(IMPORT)) {
        ImportDecl imp = parseImport();
        errorAlways(
            Code.MISPLACED_PACKAGE,
            imp.span(),
            "imports must come before all declarations and statements");
        imports.add(imp);
        continue;
      } else if (at(RBRACE)) {
        Token t = advance();
        error(Code.UNEXPECTED_TOKEN, t.span(), "unmatched '}'");
        continue;
      }
      if (isTopLevelDeclarationAhead()) {
        Decl d = parseMember(null, null);
        if (d != null) {
          members.add(d);
        }
        if (errorCount > errs) {
          synchronizeMember();
        }
      } else {
        asyncContext = true; // top-level statements run in an implicit async context
        int start = startOffset();
        Stmt s = parseStatementRecovering();
        asyncContext = savedAsync;
        members.add(new Decl.TopLevelStmt(s, spanFrom(start)));
      }
      if (pos == before) {
        advance();
      }
    }
    return new CompilationUnit(
        file, fileAnns, pkg, imports, members, new Span(0, file.content().length()));
  }

  private PackageDecl parsePackage() {
    int start = startOffset();
    expect(PACKAGE);
    String name = parseDottedName();
    expect(SEMI);
    return new PackageDecl(name, spanFrom(start));
  }

  private String parseDottedName() {
    StringBuilder sb = new StringBuilder(expectIdent("name"));
    while (at(DOT) && peek(1).is(IDENTIFIER)) {
      advance();
      sb.append('.').append(advance().text());
    }
    return sb.toString();
  }

  private ImportDecl parseImport() {
    int start = startOffset();
    expect(IMPORT);
    boolean isStatic = accept(STATIC);
    String name = parseDottedName();
    boolean wildcard = false;
    if (at(DOT) && peek(1).is(STAR)) {
      advance();
      advance();
      wildcard = true;
    }
    String alias = null;
    if (acceptContextual("as")) {
      if (wildcard) {
        error(
            Code.UNEXPECTED_TOKEN, tokAt(pos - 1).span(), "a wildcard import cannot have an alias");
      }
      alias = expectIdent("import alias");
    }
    expect(SEMI);
    return new ImportDecl(isStatic, name, wildcard, alias, spanFrom(start));
  }

  /**
   * At the top level, a declaration starts with modifiers/annotations, a type keyword, or {@code
   * Type name(} / {@code Type name<} (a function). Variable declarations without modifiers are
   * statements (locals of the implicit entry point).
   */
  private boolean isTopLevelDeclarationAhead() {
    TokenKind k = tok().kind();
    if (k == CLASS || k == INTERFACE || k == ENUM || k == OPERATOR) {
      return true;
    }
    if (isModifierStart(pos)) {
      return true;
    }
    if (atContextual("record") && peek(1).is(IDENTIFIER)) {
      return true;
    }
    int j = scanType(pos, true);
    if (j > 0 && kind(j) == IDENTIFIER) {
      TokenKind after = kind(j + 1);
      if (after == LT) {
        return true;
      }
      if (after == LPAREN) {
        int m = matching(j + 1);
        TokenKind follow = kind(m + 1);
        return m > 0
            && (follow == LBRACE
                || follow == ARROW
                || follow == THROWS
                || tokAt(m + 1).isContextual("where"));
      }
      return after == LBRACE || after == ARROW;
    }
    return false;
  }

  private static final Set<TokenKind> MEMBER_SYNC =
      Set.of(
          RBRACE, PUBLIC, PRIVATE, PROTECTED, INTERNAL, STATIC, CLASS, INTERFACE, ENUM, ABSTRACT,
          AT);

  private void synchronizeMember() {
    if (pos > 0 && (toks.get(pos - 1).is(SEMI) || toks.get(pos - 1).is(RBRACE))) {
      return;
    }
    skipUntil(MEMBER_SYNC, true);
  }

  // ------------------------------------------------------------------ type declarations

  @Override
  Decl.TypeDecl parseTypeDecl(Modifiers mods) {
    int start = mods.isEmpty() ? startOffset() : mods.span().start();
    Decl.TypeKind kind;
    Token kw = tok();
    if (accept(CLASS)) {
      kind = Decl.TypeKind.CLASS;
    } else if (accept(INTERFACE)) {
      kind = Decl.TypeKind.INTERFACE;
    } else if (accept(ENUM)) {
      kind = Decl.TypeKind.ENUM;
    } else if (acceptContextual("record")) {
      kind = Decl.TypeKind.RECORD;
    } else {
      reportExpected("'class', 'interface', 'record' or 'enum'");
      kind = Decl.TypeKind.CLASS;
    }
    Span nameSpan = tok().span();
    String name = expectIdent(kind.keyword() + " name");
    List<TypeParam> typeParams = at(LT) ? parseTypeParams() : List.of();
    List<Param> header = null;
    if (at(LPAREN)) {
      if (kind == Decl.TypeKind.RECORD || kind == Decl.TypeKind.ENUM) {
        header = parseParams();
      } else {
        errorAt(
            Code.UNEXPECTED_TOKEN,
            tok().span(),
            "only records and enums have a parameter list in their header",
            kind == Decl.TypeKind.CLASS ? "declare a constructor, or use 'record'" : null);
        parseParams();
      }
    } else if (kind == Decl.TypeKind.RECORD) {
      reportExpected("record component list '('");
      header = List.of();
    }
    List<TypeNode> supertypes = new ArrayList<>();
    if (at(COLON)) {
      advance();
      do {
        supertypes.add(parseType(false));
      } while (accept(COMMA));
    } else if (at(EXTENDS) || at(IMPLEMENTS)) {
      Token t = advance();
      errorAt(
          Code.UNEXPECTED_TOKEN,
          t.span(),
          "J# uses ':' for inheritance, not '" + t.text() + "'",
          "write 'class A : Base, Interface'");
      do {
        supertypes.add(parseType(false));
      } while (accept(COMMA) || accept(IMPLEMENTS));
    }
    List<TypeNode> permits = null;
    if (atContextual("permits")) {
      advance();
      permits = new ArrayList<>();
      do {
        permits.add(parseType(false));
      } while (accept(COMMA));
    }
    typeParams = parseWhereClauses(typeParams);
    List<EnumConstant> constants = new ArrayList<>();
    List<Decl> members;
    if (accept(SEMI)) {
      members = List.of();
    } else if (at(LBRACE)) {
      if (kind == Decl.TypeKind.ENUM) {
        members = parseEnumBody(name, constants);
      } else {
        members = parseClassBody(name, kind);
      }
    } else {
      reportExpected("'{' or ';'");
      members = List.of();
    }
    return new Decl.TypeDecl(
        kind,
        mods,
        name,
        nameSpan,
        typeParams,
        header,
        supertypes,
        permits,
        constants,
        members,
        new Span(start, Math.max(start, prevEnd())));
  }

  @Override
  List<Decl> parseClassBody(String className, Decl.TypeKind kind) {
    expect(LBRACE);
    List<Decl> members = new ArrayList<>();
    parseMembersUntilBrace(className, kind, members);
    expect(RBRACE);
    return members;
  }

  private void parseMembersUntilBrace(String className, Decl.TypeKind kind, List<Decl> members) {
    while (!at(RBRACE) && !atEof()) {
      int before = pos;
      int errs = errorCount;
      if (accept(SEMI)) {
        continue;
      }
      Decl d = parseMember(className, kind);
      if (d != null) {
        members.add(d);
      }
      if (errorCount > errs) {
        synchronizeMember();
      }
      if (pos == before) {
        advance();
      }
    }
  }

  private List<Decl> parseEnumBody(String name, List<EnumConstant> constants) {
    expect(LBRACE);
    while (at(IDENTIFIER) || at(AT)) {
      int start = startOffset();
      int before = pos;
      List<Annotation> anns = new ArrayList<>();
      while (at(AT)) {
        anns.add(parseAnnotation());
      }
      Span nameSpan = tok().span();
      String cname = expectIdent("enum constant name");
      List<Arg> args = at(LPAREN) ? parseArgs() : null;
      if (at(LBRACE)) {
        errorAt(
            Code.UNSUPPORTED_SYNTAX,
            tok().span(),
            "enum constants with bodies are not supported",
            "move the behavior into a method that switches on 'this'");
        int m = matching(pos);
        if (m > 0) {
          pos = m + 1;
        }
      }
      constants.add(new EnumConstant(anns, cname, nameSpan, args, spanFrom(start)));
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    List<Decl> members = new ArrayList<>();
    if (accept(SEMI)) {
      parseMembersUntilBrace(name, Decl.TypeKind.ENUM, members);
    } else if (!at(RBRACE)) {
      reportExpected("',', ';' or '}' in enum body");
      parseMembersUntilBrace(name, Decl.TypeKind.ENUM, members);
    }
    expect(RBRACE);
    return members;
  }

  // ------------------------------------------------------------------ members

  /**
   * Parses a member. {@code className} is null at the top level of a file. Returns null if nothing
   * usable was parsed.
   */
  private Decl parseMember(String className, Decl.TypeKind ownerKind) {
    int start = startOffset();
    Modifiers mods = parseModifiers();
    int declStart = mods.isEmpty() ? start : mods.span().start();
    Token t = tok();
    if (t.is(CLASS)
        || t.is(INTERFACE)
        || t.is(ENUM)
        || (t.isContextual("record") && peek(1).is(IDENTIFIER))) {
      return parseTypeDecl(mods);
    }
    if (className != null && t.isContextual("init") && peek(1).is(LBRACE)) {
      // `init { ... }`: a record's compact constructor (validate or normalize the components),
      // or an instance initializer of a class (runs in every constructor). D076.
      advance();
      for (Modifiers.Item item : mods.list()) {
        errorAlways(Code.UNEXPECTED_TOKEN, item.span(), "'init' blocks take no modifiers");
      }
      Stmt.Block body = parseBlock();
      if (ownerKind == Decl.TypeKind.RECORD) {
        Modifiers pub =
            new Modifiers(
                List.of(new Modifiers.Item(Modifier.PUBLIC, t.span())),
                mods.annotations(),
                t.span());
        return new Decl.Constructor(
            pub, className, t.span(), null, new Body.Block(body), spanFrom(declStart));
      }
      return new Decl.Initializer(false, body, spanFrom(declStart));
    }
    if (t.is(LBRACE) && className != null) {
      Stmt.Block body = parseBlock();
      for (Modifiers.Item item : mods.list()) {
        if (item.modifier() != Modifier.STATIC) {
          errorAlways(
              Code.UNEXPECTED_TOKEN, item.span(), "initializer blocks can only be 'static'");
        }
      }
      return new Decl.Initializer(mods.has(Modifier.STATIC), body, spanFrom(declStart));
    }
    if (className != null && t.is(IDENTIFIER) && t.text().equals(className)) {
      if (peek(1).is(LPAREN)) {
        advance();
        List<Param> params = parseParams();
        Body body = parseMethodBody(mods, true);
        return new Decl.Constructor(mods, className, t.span(), params, body, spanFrom(declStart));
      }
      if (peek(1).is(LBRACE) && ownerKind == Decl.TypeKind.RECORD) {
        advance();
        errorAlways(
            Code.UNEXPECTED_TOKEN,
            t.span(),
            "record validation is written 'init { ... }'",
            "replace '" + className + " {' with 'init {'");
        Stmt.Block body = parseBlock();
        return new Decl.Constructor(
            mods, className, t.span(), null, new Body.Block(body), spanFrom(declStart));
      }
    }
    if ((t.isContextual("var") || t.isContextual("val")) && peek(1).is(IDENTIFIER)) {
      LocalKind kind = advance().text().equals("var") ? LocalKind.VAR : LocalKind.VAL;
      List<VarDeclarator> vars = parseDeclarators();
      expect(SEMI);
      return new Decl.Field(mods, kind, null, vars, spanFrom(declStart));
    }
    // Method with omitted return type: `private helper(int x) => ...`.
    if (t.is(IDENTIFIER) && peek(1).is(LPAREN)) {
      int m = matching(pos + 1);
      if (m > 0 && (kind(m + 1) == ARROW || kind(m + 1) == LBRACE)) {
        return parseMethodRest(mods, declStart, List.of(), null);
      }
    }
    if (t.is(OPERATOR) || (startsType(t.kind()) && kind(scanType(pos, true)) == OPERATOR)) {
      if (!t.is(OPERATOR)) {
        parseType();
      }
      Token op = advance();
      errorAt(
          Code.UNSUPPORTED_SYNTAX,
          op.span(),
          "operator overloading is reserved for J# v0.2",
          "declare a named method instead");
      skipUntil(Set.of(LBRACE, ARROW), true);
      if (at(LBRACE)) {
        parseBlock();
      } else if (accept(ARROW)) {
        parseExpr();
        expect(SEMI);
      }
      return null;
    }
    List<TypeParam> javaStyleTypeParams = List.of();
    if (t.is(LT)) {
      int ltPos = pos;
      javaStyleTypeParams = parseTypeParams();
      errorAt(
          Code.UNEXPECTED_TOKEN,
          tokAt(ltPos).span(),
          "type parameters of a method go after its name",
          "write 'ReturnType name<T>(...)' instead of '<T> ReturnType name(...)'");
      t = tok();
    }
    if (!startsType(t.kind())) {
      errorAt(
          Code.EXPECTED_DECLARATION,
          t.span(),
          "expected a declaration, found " + t.describe(),
          className == null ? null : null);
      return null;
    }
    TypeNode type = parseType();
    if (at(LPAREN) && className != null) {
      // `Name(` where Name differs from the class: probably a misspelled constructor.
      errorAt(
          Code.EXPECTED_IDENTIFIER,
          type.span(),
          "method is missing a name or return type",
          "constructors must be named like their class ('" + className + "')");
      parseParams();
      parseMethodBody(mods, false);
      return null;
    }
    Span nameSpan = tok().span();
    String name = expectIdent("member name");
    if (at(LT) || at(LPAREN)) {
      List<TypeParam> tps = at(LT) ? parseTypeParams() : javaStyleTypeParams;
      return parseMethodNamed(mods, declStart, tps, type, name, nameSpan);
    }
    if (at(LBRACE)) {
      return parseProperty(mods, declStart, type, name, nameSpan);
    }
    if (at(ARROW)) {
      advance();
      Expr getter = parseExpr();
      expect(SEMI);
      return new Decl.Property(mods, type, name, nameSpan, null, getter, null, spanFrom(declStart));
    }
    // Field: first declarator name already consumed.
    List<VarDeclarator> vars = new ArrayList<>();
    Expr init = null;
    if (accept(EQ)) {
      init = at(LBRACE) ? parseArrayInit() : parseExpr();
    }
    vars.add(new VarDeclarator(name, nameSpan, init, spanFrom(nameSpan.start())));
    if (accept(COMMA)) {
      vars.addAll(parseDeclarators());
    }
    expect(SEMI);
    return new Decl.Field(mods, LocalKind.TYPED, type, vars, spanFrom(declStart));
  }

  private List<VarDeclarator> parseDeclarators() {
    List<VarDeclarator> vars = new ArrayList<>();
    do {
      int ds = startOffset();
      Span nameSpan = tok().span();
      String name = expectIdent("variable name");
      Expr init = null;
      if (accept(EQ)) {
        init = at(LBRACE) ? parseArrayInit() : parseExpr();
      }
      vars.add(new VarDeclarator(name, nameSpan, init, spanFrom(ds)));
    } while (accept(COMMA));
    return vars;
  }

  private Decl parseMethodRest(
      Modifiers mods, int declStart, List<TypeParam> tps, TypeNode returnType) {
    Span nameSpan = tok().span();
    String name = expectIdent("method name");
    return parseMethodNamed(mods, declStart, tps, returnType, name, nameSpan);
  }

  private Decl parseMethodNamed(
      Modifiers mods,
      int declStart,
      List<TypeParam> tps,
      TypeNode returnType,
      String name,
      Span nameSpan) {
    List<Param> params = parseParams();
    tps = parseWhereClauses(tps);
    if (at(THROWS)) {
      Token t = advance();
      errorAt(
          Code.UNEXPECTED_TOKEN,
          t.span(),
          "J# has no checked exceptions; 'throws' clauses are not allowed",
          "remove the throws clause");
      do {
        parseType(false);
      } while (accept(COMMA));
    }
    Body body = parseMethodBody(mods, false);
    return new Decl.Method(
        mods, tps, returnType, name, nameSpan, params, body, spanFrom(declStart));
  }

  /** Parses {@code { ... }}, {@code => expr;} or {@code ;} (returns null). */
  private Body parseMethodBody(Modifiers mods, boolean isConstructor) {
    boolean saved = asyncContext;
    asyncContext = mods.has(Modifier.ASYNC);
    try {
      if (at(LBRACE)) {
        return new Body.Block(parseBlock());
      }
      if (accept(ARROW)) {
        Expr e = parseExpr();
        expect(SEMI);
        return new Body.ExprBody(e);
      }
      if (accept(SEMI)) {
        if (isConstructor) {
          error(Code.EXPECTED_TOKEN, tokAt(pos - 1).span(), "a constructor needs a body");
        }
        return null;
      }
      reportExpected("method body '{', '=>' or ';'");
      return null;
    } finally {
      asyncContext = saved;
    }
  }

  /** Parses a parenthesized parameter list. */
  final List<Param> parseParams() {
    expect(LPAREN);
    List<Param> params = new ArrayList<>();
    while (!at(RPAREN) && !atEof()) {
      int start = startOffset();
      int before = pos;
      Modifiers mods = parseModifiers();
      for (Modifiers.Item item : mods.list()) {
        if (item.modifier() != Modifier.FINAL) {
          errorAlways(
              Code.UNEXPECTED_TOKEN,
              item.span(),
              "modifier '" + item.modifier().keyword() + "' is not allowed on a parameter");
        }
      }
      boolean isThis = accept(THIS);
      boolean isParams = acceptContextual("params") && startsType(tok().kind());
      TypeNode type = parseType();
      if (at(DOTDOT) && peek(1).is(DOT)) {
        errorAt(
            Code.UNEXPECTED_TOKEN,
            tok().span(),
            "varargs are declared with 'params'",
            "write 'params T[] name'");
        advance();
        advance();
      }
      Span nameSpan = tok().span();
      String name = expectIdent("parameter name");
      Expr def = accept(EQ) ? parseExpr() : null;
      params.add(new Param(mods, isThis, isParams, type, name, nameSpan, def, spanFrom(start)));
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RPAREN);
    return params;
  }

  private Decl parseProperty(
      Modifiers mods, int declStart, TypeNode type, String name, Span nameSpan) {
    expect(LBRACE);
    List<Accessor> accessors = new ArrayList<>();
    while (!at(RBRACE) && !atEof()) {
      int as = startOffset();
      int before = pos;
      Modifiers amods = parseModifiers();
      Accessor.Kind kind;
      if (atContextual("get")) {
        kind = Accessor.Kind.GET;
      } else if (atContextual("set")) {
        kind = Accessor.Kind.SET;
      } else if (atContextual("init")) {
        kind = Accessor.Kind.INIT;
      } else {
        errorAt(
            Code.INVALID_ACCESSOR,
            tok().span(),
            "expected 'get', 'set' or 'init', found " + tok().describe(),
            null);
        skipUntil(Set.of(RBRACE), true);
        if (pos == before) {
          break;
        }
        continue;
      }
      advance();
      Body body = null;
      if (at(LBRACE)) {
        body = new Body.Block(parseBlock());
      } else if (accept(ARROW)) {
        body = new Body.ExprBody(parseExpr());
        expect(SEMI);
      } else {
        expect(SEMI);
      }
      accessors.add(new Accessor(amods, kind, body, spanFrom(as)));
    }
    expect(RBRACE);
    Expr init = null;
    if (accept(EQ)) {
      init = parseExpr();
      expect(SEMI);
    }
    return new Decl.Property(
        mods, type, name, nameSpan, accessors, null, init, spanFrom(declStart));
  }
}
