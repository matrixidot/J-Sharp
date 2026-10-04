package io.github.matrixidot.jsharp.compiler.syntax;

import static io.github.matrixidot.jsharp.compiler.syntax.TokenKind.*;

import io.github.matrixidot.jsharp.compiler.ast.Arg;
import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.Expr.AssignOp;
import io.github.matrixidot.jsharp.compiler.ast.Expr.BinaryOp;
import io.github.matrixidot.jsharp.compiler.ast.Expr.UnaryOp;
import io.github.matrixidot.jsharp.compiler.ast.FieldInit;
import io.github.matrixidot.jsharp.compiler.ast.Modifiers;
import io.github.matrixidot.jsharp.compiler.ast.Param;
import io.github.matrixidot.jsharp.compiler.ast.Pattern;
import io.github.matrixidot.jsharp.compiler.ast.SwitchArm;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticSink;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Expression and pattern parsing: precedence climbing for binary operators, with lambdas, casts,
 * tuples and generic calls disambiguated by side-effect-free lookahead.
 *
 * <p>Precedence (loosest first): assignment/lambda, {@code ?:}, {@code ??}, {@code ||}, {@code &&},
 * {@code |}, {@code ^}, {@code &}, equality, relational/{@code is}/{@code as}, shift, range {@code
 * ..}, additive, multiplicative, {@code switch}/{@code with}, unary, postfix.
 */
abstract class ExprParser extends ParserBase {
  static final int P_COALESCE = 3;
  static final int P_OR = 4;
  static final int P_AND = 5;
  static final int P_BIT_OR = 6;
  static final int P_BIT_XOR = 7;
  static final int P_BIT_AND = 8;
  static final int P_EQUALITY = 9;
  static final int P_RELATIONAL = 10;
  static final int P_SHIFT = 11;
  static final int P_RANGE = 12;
  static final int P_ADDITIVE = 13;
  static final int P_MULTIPLICATIVE = 14;
  static final int P_SWITCH = 15;

  ExprParser(SourceFile file, List<Token> toks, DiagnosticSink sink) {
    super(file, toks, sink);
  }

  /** Creates a parser over a sub-token-stream (used for interpolation holes). */
  abstract ExprParser subParser(List<Token> tokens);

  // ------------------------------------------------------------------ entry points

  @Override
  final Expr parseExpr() {
    enter();
    try {
      return parseAssignment();
    } finally {
      exit();
    }
  }

  final Expr parseAssignment() {
    if (isLambdaStart()) {
      return parseLambda();
    }
    int start = startOffset();
    Expr left = parseConditional();
    int[] count = new int[1];
    AssignOp op = assignOpAt(count);
    if (op != null) {
      for (int i = 0; i < count[0]; i++) {
        advance();
      }
      Expr right = parseExpr();
      return new Expr.Assign(op, left, right, spanFrom(start));
    }
    return left;
  }

  private AssignOp assignOpAt(int[] count) {
    count[0] = 1;
    return switch (tok().kind()) {
      case EQ -> AssignOp.ASSIGN;
      case PLUS_EQ -> AssignOp.ADD;
      case MINUS_EQ -> AssignOp.SUB;
      case STAR_EQ -> AssignOp.MUL;
      case SLASH_EQ -> AssignOp.DIV;
      case PERCENT_EQ -> AssignOp.REM;
      case AMP_EQ -> AssignOp.AND;
      case BAR_EQ -> AssignOp.OR;
      case CARET_EQ -> AssignOp.XOR;
      case LTLT_EQ -> AssignOp.SHL;
      case QUESTION_QUESTION_EQ -> AssignOp.COALESCE;
      case GT -> {
        // >>= and >>>= are lexed as separate adjacent tokens.
        if (kind(pos + 1) == GT && adjacent(pos)) {
          if (kind(pos + 2) == EQ && adjacent(pos + 1)) {
            count[0] = 3;
            yield AssignOp.SHR;
          }
          if (kind(pos + 2) == GT
              && adjacent(pos + 1)
              && kind(pos + 3) == EQ
              && adjacent(pos + 2)) {
            count[0] = 4;
            yield AssignOp.USHR;
          }
        }
        yield null;
      }
      default -> null;
    };
  }

  /** {@code cond ? a : b}; also the entry point for contexts where lambdas are not allowed. */
  final Expr parseConditional() {
    int start = startOffset();
    Expr cond = parseBinary(P_COALESCE);
    if (at(QUESTION)) {
      advance();
      Expr then = parseExpr();
      expect(COLON);
      Expr otherwise = parseExpr();
      return new Expr.Conditional(cond, then, otherwise, false, spanFrom(start));
    }
    return cond;
  }

  // ------------------------------------------------------------------ binary operators

  private record Infix(int prec, BinaryOp op, int tokens, String special) {}

  private Infix infixAt() {
    Token t = tok();
    return switch (t.kind()) {
      case QUESTION_QUESTION -> new Infix(P_COALESCE, BinaryOp.COALESCE, 1, null);
      case BARBAR -> new Infix(P_OR, BinaryOp.OR, 1, null);
      case AMPAMP -> new Infix(P_AND, BinaryOp.AND, 1, null);
      case BAR -> new Infix(P_BIT_OR, BinaryOp.BIT_OR, 1, null);
      case CARET -> new Infix(P_BIT_XOR, BinaryOp.BIT_XOR, 1, null);
      case AMP -> new Infix(P_BIT_AND, BinaryOp.BIT_AND, 1, null);
      case EQEQ -> new Infix(P_EQUALITY, BinaryOp.EQ, 1, null);
      case BANG_EQ -> new Infix(P_EQUALITY, BinaryOp.NE, 1, null);
      case EQEQEQ -> new Infix(P_EQUALITY, BinaryOp.REF_EQ, 1, null);
      case BANG_EQEQ -> new Infix(P_EQUALITY, BinaryOp.REF_NE, 1, null);
      case LT -> new Infix(P_RELATIONAL, BinaryOp.LT, 1, null);
      case LE -> new Infix(P_RELATIONAL, BinaryOp.LE, 1, null);
      case GT -> gtInfix();
      case LTLT -> new Infix(P_SHIFT, BinaryOp.SHL, 1, null);
      case DOTDOT -> new Infix(P_RANGE, null, 1, "..");
      case PLUS -> new Infix(P_ADDITIVE, BinaryOp.ADD, 1, null);
      case MINUS -> new Infix(P_ADDITIVE, BinaryOp.SUB, 1, null);
      case STAR -> new Infix(P_MULTIPLICATIVE, BinaryOp.MUL, 1, null);
      case SLASH -> new Infix(P_MULTIPLICATIVE, BinaryOp.DIV, 1, null);
      case PERCENT -> new Infix(P_MULTIPLICATIVE, BinaryOp.REM, 1, null);
      case SWITCH -> peek(1).is(LBRACE) ? new Infix(P_SWITCH, null, 1, "switch") : null;
      case IDENTIFIER -> {
        if (t.isContextual("is")) {
          yield new Infix(P_RELATIONAL, null, 1, "is");
        }
        if (t.isContextual("as")) {
          yield new Infix(P_RELATIONAL, null, 1, "as");
        }
        if (t.isContextual("with") && peek(1).is(LBRACE)) {
          yield new Infix(P_SWITCH, null, 1, "with");
        }
        yield null;
      }
      default -> null;
    };
  }

  private Infix gtInfix() {
    if (kind(pos + 1) == GT && adjacent(pos)) {
      if (kind(pos + 2) == GT && adjacent(pos + 1)) {
        if (kind(pos + 3) == EQ && adjacent(pos + 2)) {
          return null; // >>>=
        }
        return new Infix(P_SHIFT, BinaryOp.USHR, 3, null);
      }
      if (kind(pos + 2) == EQ && adjacent(pos + 1)) {
        return null; // >>=
      }
      return new Infix(P_SHIFT, BinaryOp.SHR, 2, null);
    }
    if (kind(pos + 1) == EQ && adjacent(pos)) {
      return new Infix(P_RELATIONAL, BinaryOp.GE, 2, null);
    }
    return new Infix(P_RELATIONAL, BinaryOp.GT, 1, null);
  }

  final Expr parseBinary(int minPrec) {
    enter();
    try {
      int start = startOffset();
      Expr left = parseUnary();
      while (true) {
        Infix in = infixAt();
        if (in == null || in.prec() < minPrec) {
          return left;
        }
        for (int i = 0; i < in.tokens(); i++) {
          advance();
        }
        if (in.special() == null) {
          boolean rightAssoc = in.op() == BinaryOp.COALESCE;
          Expr right = parseBinary(rightAssoc ? in.prec() : in.prec() + 1);
          left = new Expr.Binary(in.op(), left, right, spanFrom(start));
          continue;
        }
        switch (in.special()) {
          case "is" -> {
            Pattern p = parsePattern();
            left = new Expr.Is(left, p, spanFrom(start));
          }
          case "as" -> {
            TypeNode type = parseType(false);
            left = new Expr.As(left, type, spanFrom(start));
          }
          case ".." -> {
            Expr right = canStartExpression(tok()) ? parseBinary(P_ADDITIVE) : null;
            left = new Expr.Range(left, right, spanFrom(start));
          }
          case "switch" -> left = new Expr.Switch(left, parseSwitchArms(), spanFrom(start));
          case "with" -> left = new Expr.With(left, parseFieldInits(), spanFrom(start));
          default -> throw new IllegalStateException(in.special());
        }
      }
    } finally {
      exit();
    }
  }

  // ------------------------------------------------------------------ unary / postfix

  final Expr parseUnary() {
    enter();
    try {
      int start = startOffset();
      Token t = tok();
      UnaryOp prefix =
          switch (t.kind()) {
            case MINUS -> UnaryOp.NEG;
            case PLUS -> UnaryOp.PLUS;
            case BANG -> UnaryOp.NOT;
            case TILDE -> UnaryOp.BIT_NOT;
            case PLUSPLUS -> UnaryOp.PRE_INC;
            case MINUSMINUS -> UnaryOp.PRE_DEC;
            case CARET -> UnaryOp.FROM_END;
            default -> null;
          };
      if (prefix != null) {
        advance();
        Expr operand = parseUnary();
        return new Expr.Unary(prefix, operand, spanFrom(start));
      }
      if (t.is(DOTDOT)) {
        advance();
        Expr to = canStartExpression(tok()) ? parseBinary(P_ADDITIVE) : null;
        return new Expr.Range(null, to, spanFrom(start));
      }
      if (t.is(THROW)) {
        advance();
        Expr e = parseExpr();
        return new Expr.Throw(e, spanFrom(start));
      }
      if (t.isContextual("await") && isAwaitOperandStart(peek(1))) {
        advance();
        Expr e = parseUnary();
        return new Expr.Await(e, spanFrom(start));
      }
      if (t.is(LPAREN) && isCastAhead()) {
        advance();
        TypeNode type = parseType();
        expect(RPAREN);
        Expr operand = parseUnary();
        return new Expr.Cast(type, operand, spanFrom(start));
      }
      return parsePostfix(parsePrimary(), start);
    } finally {
      exit();
    }
  }

  private boolean isAwaitOperandStart(Token next) {
    if (asyncContext) {
      return canStartExpression(next) && !next.is(MINUS) && !next.is(PLUS);
    }
    return switch (next.kind()) {
      case IDENTIFIER, NEW, THIS, INT_LITERAL, STRING_LITERAL, INTERP_STRING ->
          !isContextualOperator(next);
      default -> false;
    };
  }

  private static final Set<String> CONTEXTUAL_OPERATORS =
      Set.of("is", "as", "with", "and", "or", "when", "in");

  static boolean isContextualOperator(Token t) {
    return t.kind() == IDENTIFIER && !t.escaped() && CONTEXTUAL_OPERATORS.contains(t.text());
  }

  /** True if the cursor is at {@code (Type)} followed by something that makes it a cast. */
  private boolean isCastAhead() {
    int j = scanType(pos + 1, true);
    if (j < 0 || kind(j) != RPAREN) {
      return false;
    }
    Token after = tokAt(j + 1);
    if (isPrimitiveKind(kind(pos + 1))) {
      return canStartExpression(after) && kind(pos + 1) != VOID;
    }
    return switch (after.kind()) {
      case IDENTIFIER -> !isContextualOperator(after);
      case INT_LITERAL,
          LONG_LITERAL,
          FLOAT_LITERAL,
          DOUBLE_LITERAL,
          CHAR_LITERAL,
          STRING_LITERAL,
          INTERP_STRING,
          LPAREN,
          THIS,
          NEW,
          SUPER,
          TYPEOF,
          TILDE,
          NULL,
          TRUE,
          FALSE ->
          true;
      default -> false;
    };
  }

  static boolean canStartExpression(Token t) {
    return switch (t.kind()) {
      case IDENTIFIER,
          INT_LITERAL,
          LONG_LITERAL,
          FLOAT_LITERAL,
          DOUBLE_LITERAL,
          CHAR_LITERAL,
          STRING_LITERAL,
          INTERP_STRING,
          NEW,
          THIS,
          SUPER,
          LPAREN,
          TYPEOF,
          SWITCH,
          IF,
          MINUS,
          PLUS,
          BANG,
          TILDE,
          PLUSPLUS,
          MINUSMINUS,
          CARET,
          DOTDOT,
          THROW,
          NULL,
          TRUE,
          FALSE,
          BOOLEAN,
          BYTE,
          CHAR,
          SHORT,
          INT,
          LONG,
          FLOAT,
          DOUBLE ->
          !isContextualOperator(t);
      default -> false;
    };
  }

  final Expr parsePostfix(Expr e, int start) {
    while (true) {
      Token t = tok();
      switch (t.kind()) {
        case DOT, QUESTION_DOT -> {
          boolean nullSafe = t.is(QUESTION_DOT);
          advance();
          if (at(THIS) && !nullSafe) {
            advance();
            e = new Expr.This(qualifiedName(e), spanFrom(start));
            continue;
          }
          if (at(SUPER)) {
            advance();
            e = new Expr.Super(e, spanFrom(start));
            continue;
          }
          if (at(CLASS)) {
            Token c = advance();
            errorAt(
                Code.UNEXPECTED_TOKEN,
                c.span(),
                "class literals are written typeof(T) in J#",
                "use typeof(...) instead of .class");
            e = new Expr.Error(spanFrom(start));
            continue;
          }
          Span nameSpan = tok().span();
          String name = expectIdent("member name");
          List<TypeNode> targs = genericCallArgsAhead() ? parseTypeArgs() : List.of();
          e = new Expr.Member(e, name, nameSpan, targs, nullSafe, spanFrom(start));
        }
        case QUESTION -> {
          if (kind(pos + 1) == LBRACKET && adjacent(pos)) {
            advance();
            advance();
            Expr index = parseExpr();
            expect(RBRACKET);
            e = new Expr.Index(e, index, true, spanFrom(start));
          } else {
            return e;
          }
        }
        case LPAREN -> {
          List<Arg> args = parseArgs();
          e = new Expr.Call(e, args, spanFrom(start));
        }
        case LBRACKET -> {
          advance();
          Expr index = parseExpr();
          expect(RBRACKET);
          e = new Expr.Index(e, index, false, spanFrom(start));
        }
        case PLUSPLUS -> {
          advance();
          e = new Expr.Unary(UnaryOp.POST_INC, e, spanFrom(start));
        }
        case MINUSMINUS -> {
          advance();
          e = new Expr.Unary(UnaryOp.POST_DEC, e, spanFrom(start));
        }
        case BANG -> {
          advance();
          e = new Expr.Unary(UnaryOp.NON_NULL, e, spanFrom(start));
        }
        case COLONCOLON -> {
          advance();
          Span nameSpan = tok().span();
          String name = accept(NEW) ? "new" : expectIdent("method name");
          e = new Expr.MethodRef(e, null, name, nameSpan, spanFrom(start));
        }
        default -> {
          return e;
        }
      }
    }
  }

  private static String qualifiedName(Expr e) {
    return switch (e) {
      case Expr.Name n -> n.name();
      case Expr.Member m -> qualifiedName(m.target()) + "." + m.name();
      default -> "<error>";
    };
  }

  /** True at {@code <} when it opens type arguments of a generic call or method reference. */
  private boolean genericCallArgsAhead() {
    if (!at(LT)) {
      return false;
    }
    int j = scanTypeArgs(pos);
    return j > 0 && (kind(j) == LPAREN || kind(j) == COLONCOLON);
  }

  final List<Arg> parseArgs() {
    expect(LPAREN);
    List<Arg> args = new ArrayList<>();
    while (!at(RPAREN) && !atEof()) {
      int start = startOffset();
      int before = pos;
      String name = null;
      if (at(IDENTIFIER) && peek(1).is(COLON)) {
        name = advance().text();
        advance();
      }
      refOutModeAhead(true);
      Expr value = parseExpr();
      args.add(new Arg(name, value, spanFrom(start)));
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RPAREN);
    return args;
  }

  // ------------------------------------------------------------------ primary

  final Expr parsePrimary() {
    int start = startOffset();
    Token t = tok();
    switch (t.kind()) {
      case INT_LITERAL -> {
        advance();
        return new Expr.Literal(Expr.LiteralKind.INT, t.value(), t.text(), t.span());
      }
      case LONG_LITERAL -> {
        advance();
        return new Expr.Literal(Expr.LiteralKind.LONG, t.value(), t.text(), t.span());
      }
      case FLOAT_LITERAL -> {
        advance();
        return new Expr.Literal(Expr.LiteralKind.FLOAT, t.value(), t.text(), t.span());
      }
      case DOUBLE_LITERAL -> {
        advance();
        return new Expr.Literal(Expr.LiteralKind.DOUBLE, t.value(), t.text(), t.span());
      }
      case CHAR_LITERAL -> {
        advance();
        return new Expr.Literal(Expr.LiteralKind.CHAR, t.value(), t.text(), t.span());
      }
      case STRING_LITERAL -> {
        advance();
        return new Expr.Literal(Expr.LiteralKind.STRING, t.value(), t.text(), t.span());
      }
      case TRUE, FALSE -> {
        advance();
        return new Expr.Literal(Expr.LiteralKind.BOOLEAN, t.is(TRUE), t.text(), t.span());
      }
      case NULL -> {
        advance();
        return new Expr.Literal(Expr.LiteralKind.NULL, null, t.text(), t.span());
      }
      case INTERP_STRING -> {
        return parseInterpolated();
      }
      case IDENTIFIER -> {
        if (t.isContextual("nameof") && peek(1).is(LPAREN)) {
          advance();
          advance();
          Expr e = parseExpr();
          expect(RPAREN);
          return new Expr.NameOf(e, spanFrom(start));
        }
        if (t.isContextual("checked") && peek(1).is(LPAREN)) {
          advance();
          advance();
          Expr e = parseExpr();
          expect(RPAREN);
          return new Expr.Checked(e, spanFrom(start));
        }
        int j = scanType(pos, false);
        if (j > 0 && kind(j) == COLONCOLON && kind(j - 1) == RBRACKET) {
          // Array constructor reference of a reference type: String[]::new.
          TypeNode type = parseType(false);
          advance();
          Span nameSpan = tok().span();
          String name = accept(NEW) ? "new" : expectIdent("method name");
          return new Expr.MethodRef(null, type, name, nameSpan, spanFrom(start));
        }
        advance();
        List<TypeNode> targs = genericCallArgsAhead() ? parseTypeArgs() : List.of();
        return new Expr.Name(t.text(), targs, spanFrom(start));
      }
      case THIS -> {
        advance();
        return new Expr.This(null, t.span());
      }
      case SUPER -> {
        advance();
        return new Expr.Super(null, t.span());
      }
      case NEW -> {
        return parseNew();
      }
      case LPAREN -> {
        return parseParenOrTuple();
      }
      case TYPEOF -> {
        advance();
        expect(LPAREN);
        TypeNode type = parseType();
        expect(RPAREN);
        return new Expr.TypeOf(type, spanFrom(start));
      }
      case SWITCH -> {
        advance();
        expect(LPAREN);
        Expr sel = parseExpr();
        expect(RPAREN);
        return new Expr.Switch(sel, parseSwitchArms(), spanFrom(start));
      }
      case IF -> {
        return parseIfExpr();
      }
      case LBRACE -> {
        return parseBraceLiteral();
      }
      case LBRACKET -> {
        return parseCollectionLiteral();
      }
      default -> {
        if (isPrimitiveKind(t.kind())) {
          int j = scanType(pos, false);
          if (j > 0 && kind(j) == COLONCOLON) {
            TypeNode type = parseType(false);
            advance();
            Span nameSpan = tok().span();
            String name = accept(NEW) ? "new" : expectIdent("method name");
            return new Expr.MethodRef(null, type, name, nameSpan, spanFrom(start));
          }
        }
        errorAt(
            Code.EXPECTED_EXPRESSION, t.span(), "expected expression, found " + t.describe(), null);
        if (t.is(ERROR)) {
          advance();
        }
        return new Expr.Error(new Span(start, Math.max(start, t.end())));
      }
    }
  }

  private Expr parseIfExpr() {
    int start = startOffset();
    expect(IF);
    expect(LPAREN);
    Expr cond = parseExpr();
    expect(RPAREN);
    Expr then = parseExpr();
    if (!at(ELSE)) {
      errorAt(
          Code.MISSING_ELSE,
          tok().span(),
          "an if-expression requires an 'else' branch",
          "add 'else <expression>' or use an if statement");
      return new Expr.Conditional(cond, then, new Expr.Error(tok().span()), true, spanFrom(start));
    }
    advance();
    Expr otherwise = parseExpr();
    return new Expr.Conditional(cond, then, otherwise, true, spanFrom(start));
  }

  /** {@code [a, ..xs, b]} (trailing comma allowed). */
  final Expr parseCollectionLiteral() {
    int start = startOffset();
    expect(LBRACKET);
    List<Expr> elems = new ArrayList<>();
    while (!at(RBRACKET) && !atEof()) {
      int before = pos;
      int es = startOffset();
      if (at(DOTDOT)) {
        advance();
        elems.add(new Expr.Spread(parseExpr(), spanFrom(es)));
      } else {
        elems.add(parseExpr());
      }
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RBRACKET);
    return new Expr.CollectionLiteral(elems, spanFrom(start));
  }

  /**
   * {@code { ... }} in expression position: a map literal if the first element is followed by
   * {@code :} ({@code {"a": 1}}), else an array initializer ({@code {1, 2}}).
   */
  @Override
  final Expr parseBraceLiteral() {
    if (!(at(LBRACE) && !peek(1).is(RBRACE) && !peek(1).is(LBRACE))) {
      return parseArrayInit();
    }
    int start = startOffset();
    advance();
    int es = startOffset();
    Expr first = parseExpr();
    if (!at(COLON)) {
      // An array initializer whose first element is already parsed.
      List<Expr> elems = new ArrayList<>();
      elems.add(first);
      while (accept(COMMA) && !at(RBRACE) && !atEof()) {
        int before = pos;
        elems.add(at(LBRACE) ? parseArrayInit() : parseExpr());
        if (pos == before) {
          break;
        }
      }
      expect(RBRACE);
      return new Expr.ArrayInit(elems, spanFrom(start));
    }
    List<Expr.MapEntry> entries = new ArrayList<>();
    Expr key = first;
    while (true) {
      expect(COLON);
      Expr value = parseExpr();
      entries.add(new Expr.MapEntry(key, value, spanFrom(es)));
      if (!accept(COMMA) || at(RBRACE)) {
        break;
      }
      es = startOffset();
      int before = pos;
      key = parseExpr();
      if (pos == before) {
        break;
      }
    }
    expect(RBRACE);
    return new Expr.MapLiteral(entries, spanFrom(start));
  }

  @Override
  final Expr.ArrayInit parseArrayInit() {
    int start = startOffset();
    expect(LBRACE);
    List<Expr> elems = new ArrayList<>();
    while (!at(RBRACE) && !atEof()) {
      int before = pos;
      elems.add(at(LBRACE) ? parseArrayInit() : parseExpr());
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RBRACE);
    return new Expr.ArrayInit(elems, spanFrom(start));
  }

  private Expr parseInterpolated() {
    Token t = advance();
    List<Expr.Part> parts = new ArrayList<>();
    if (t.value() instanceof List<?> pieces) {
      for (Object o : pieces) {
        switch ((InterpPiece) o) {
          case InterpPiece.Text(String value) -> parts.add(new Expr.TextPart(value));
          case InterpPiece.Hole h
              when file.content().substring(h.exprStart(), h.exprEnd()).isBlank() ->
              parts.add(
                  new Expr.HolePart(
                      new Expr.Error(new Span(h.start(), h.end())),
                      h.format(),
                      new Span(h.start(), h.end())));
          case InterpPiece.Hole h -> {
            List<Token> sub = new Lexer(file, h.exprStart(), h.exprEnd(), sink).tokenize();
            ExprParser p = subParser(sub);
            p.asyncContext = asyncContext;
            p.depth = depth;
            Expr e = p.parseExpr();
            if (!p.atEof()) {
              p.errorHere(
                  Code.INVALID_INTERPOLATION,
                  "unexpected "
                      + p.tok().describe()
                      + " in interpolation hole"
                      + (p.at(COLON) || p.at(QUESTION)
                          ? " (wrap conditional expressions in parentheses)"
                          : ""));
            }
            parts.add(new Expr.HolePart(e, h.format(), new Span(h.start(), h.end())));
          }
        }
      }
    }
    return new Expr.Interpolated(parts, t.span());
  }

  private Expr parseParenOrTuple() {
    int start = startOffset();
    expect(LPAREN);
    if (at(RPAREN)) {
      advance();
      errorAlways(
          Code.EXPECTED_EXPRESSION, spanFrom(start), "empty parentheses are not an expression");
      return new Expr.Error(spanFrom(start));
    }
    List<Arg> elems = new ArrayList<>();
    boolean named = false;
    while (true) {
      int es = startOffset();
      int before = pos;
      String name = null;
      if (at(IDENTIFIER) && peek(1).is(COLON)) {
        name = advance().text();
        advance();
        named = true;
      }
      Expr e = parseExpr();
      elems.add(new Arg(name, e, spanFrom(es)));
      if (!at(COMMA) || pos == before) {
        break;
      }
      advance();
    }
    expect(RPAREN);
    if (elems.size() == 1) {
      if (named) {
        errorAlways(Code.INVALID_TUPLE, spanFrom(start), "a tuple needs at least two elements");
      }
      return new Expr.Paren(elems.getFirst().value(), spanFrom(start));
    }
    return new Expr.Tuple(elems, spanFrom(start));
  }

  private Expr parseNew() {
    int start = startOffset();
    expect(NEW);
    if (at(LPAREN)) {
      List<Arg> args = parseArgs();
      List<FieldInit> init = at(LBRACE) ? parseFieldInits() : null;
      return new Expr.New(null, args, init, null, spanFrom(start));
    }
    TypeNode type = parseNonArrayType(false);
    if (at(QUESTION) && peek(1).is(LBRACKET)) {
      advance(); // `new T?[n]`: an array of nullable elements
      type = new TypeNode.Nullable(type, spanFrom(start));
    } else if (at(QUESTION)) {
      Token q = advance();
      errorAlways(Code.UNEXPECTED_TOKEN, q.span(), "cannot create an instance of a nullable type");
    }
    if (at(LBRACKET)) {
      List<Expr> dims = new ArrayList<>();
      int extra = 0;
      while (at(LBRACKET)) {
        if (peek(1).is(RBRACKET)) {
          advance();
          advance();
          extra++;
        } else {
          if (extra > 0) {
            errorHere(Code.UNEXPECTED_TOKEN, "array dimension sizes must come before empty '[]'");
          }
          advance();
          dims.add(parseExpr());
          expect(RBRACKET);
        }
      }
      Expr.ArrayInit init = null;
      if (at(LBRACE)) {
        init = parseArrayInit();
        if (!dims.isEmpty()) {
          errorAlways(
              Code.UNEXPECTED_TOKEN,
              init.span(),
              "an array with an initializer cannot also specify dimension sizes");
        }
      } else if (dims.isEmpty()) {
        reportExpected("array size or initializer");
      }
      return new Expr.NewArray(
          type, dims, Math.max(0, extra - (init != null ? 1 : 0)), init, spanFrom(start));
    }
    if (at(LBRACE)) {
      return new Expr.New(type, null, parseFieldInits(), null, spanFrom(start));
    }
    if (!at(LPAREN)) {
      reportExpected("'(' or '{' after type in 'new' expression");
      return new Expr.New(type, List.of(), null, null, spanFrom(start));
    }
    List<Arg> args = parseArgs();
    if (at(LBRACE)) {
      if (isObjectInitializerAhead()) {
        return new Expr.New(type, args, parseFieldInits(), null, spanFrom(start));
      }
      String name = type instanceof TypeNode.Named n ? n.last().name() : "<anonymous>";
      List<Decl> body = parseClassBody(name, Decl.TypeKind.CLASS);
      return new Expr.New(type, args, null, body, spanFrom(start));
    }
    return new Expr.New(type, args, null, null, spanFrom(start));
  }

  /** At '{': object initializer ({@code { a = 1 }}) as opposed to an anonymous class body. */
  private boolean isObjectInitializerAhead() {
    return kind(pos + 1) == IDENTIFIER && kind(pos + 2) == EQ;
  }

  final List<FieldInit> parseFieldInits() {
    expect(LBRACE);
    List<FieldInit> out = new ArrayList<>();
    while (!at(RBRACE) && !atEof()) {
      int start = startOffset();
      int before = pos;
      Span nameSpan = tok().span();
      String name = expectIdent("member name");
      expect(EQ);
      Expr value = parseExpr();
      out.add(new FieldInit(name, nameSpan, value, spanFrom(start)));
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RBRACE);
    return out;
  }

  // ------------------------------------------------------------------ lambdas

  final boolean isLambdaStart() {
    Token t = tok();
    if (t.is(IDENTIFIER) && peek(1).is(ARROW)) {
      return true;
    }
    if (t.isContextual("async")) {
      if (peek(1).is(IDENTIFIER) && peek(2).is(ARROW)) {
        return true;
      }
      if (peek(1).is(LPAREN)) {
        int m = matching(pos + 1);
        return m > 0 && kind(m + 1) == ARROW;
      }
    }
    if (t.is(LPAREN)) {
      int m = matching(pos);
      return m > 0 && kind(m + 1) == ARROW;
    }
    return false;
  }

  private Expr parseLambda() {
    int start = startOffset();
    boolean isAsync = false;
    if (atContextual("async") && !peek(1).is(ARROW)) {
      advance();
      isAsync = true;
    }
    List<Param> params = new ArrayList<>();
    if (at(IDENTIFIER)) {
      Token n = advance();
      params.add(
          new Param(
              Modifiers.empty(n.start()), false, false, null, n.text(), n.span(), null, n.span()));
    } else {
      expect(LPAREN);
      while (!at(RPAREN) && !atEof()) {
        int ps = startOffset();
        int before = pos;
        if (at(IDENTIFIER) && (peek(1).is(COMMA) || peek(1).is(RPAREN))) {
          Token n = advance();
          params.add(
              new Param(
                  Modifiers.empty(ps), false, false, null, n.text(), n.span(), null, n.span()));
        } else {
          Modifiers mods = parseModifiers();
          TypeNode type = parseType();
          Span nameSpan = tok().span();
          String name = expectIdent("parameter name");
          params.add(new Param(mods, false, false, type, name, nameSpan, null, spanFrom(ps)));
        }
        if (!accept(COMMA) || pos == before) {
          break;
        }
      }
      expect(RPAREN);
      boolean typed = false;
      boolean untyped = false;
      for (Param p : params) {
        typed |= p.type() != null;
        untyped |= p.type() == null;
      }
      if (typed && untyped) {
        errorAlways(
            Code.INVALID_LAMBDA_PARAMETERS,
            spanFrom(start),
            "lambda parameters must be either all explicitly typed or all inferred");
      }
    }
    expect(ARROW);
    boolean saved = asyncContext;
    asyncContext = isAsync;
    try {
      Body body = at(LBRACE) ? new Body.Block(parseBlock()) : new Body.ExprBody(parseExpr());
      return new Expr.Lambda(params, isAsync, body, spanFrom(start));
    } finally {
      asyncContext = saved;
    }
  }

  // ------------------------------------------------------------------ switch expressions

  /**
   * At a '{' after '=>': a block of statements ({@code { ...; yield x; }}) rather than a map
   * literal, if a ';' appears directly inside the braces.
   */
  private boolean isBlockArm() {
    int end = matching(pos);
    int depth = 0;
    for (int i = pos + 1; end > 0 && i < end; i++) {
      switch (kind(i)) {
        case LPAREN, LBRACKET, LBRACE -> depth++;
        case RPAREN, RBRACKET, RBRACE -> depth--;
        case SEMI -> {
          if (depth == 0) {
            return true;
          }
        }
        default -> {}
      }
    }
    return false;
  }

  final List<SwitchArm> parseSwitchArms() {
    expect(LBRACE);
    List<SwitchArm> arms = new ArrayList<>();
    while (!at(RBRACE) && !atEof()) {
      int start = startOffset();
      int before = pos;
      Pattern p = parsePattern();
      Expr guard = acceptContextual("when") ? parseConditional() : null;
      expect(ARROW);
      int bodyStart = startOffset();
      Expr body =
          at(LBRACE) && isBlockArm()
              ? new Expr.BlockExpr(parseBlock(), spanFrom(bodyStart))
              : parseExpr();
      arms.add(new SwitchArm(p, guard, body, spanFrom(start)));
      if (!accept(COMMA)) {
        if (!at(RBRACE) && pos != before) {
          reportExpected("',' or '}' after switch arm");
          skipUntil(Set.of(RBRACE), false);
        }
        break;
      }
      if (pos == before) {
        break;
      }
    }
    expect(RBRACE);
    return arms;
  }

  // ------------------------------------------------------------------ patterns

  final Pattern parsePattern() {
    enter();
    try {
      int start = startOffset();
      Pattern left = parseAndPattern();
      while (atContextual("or")) {
        advance();
        Pattern right = parseAndPattern();
        left = new Pattern.Or(left, right, spanFrom(start));
      }
      return left;
    } finally {
      exit();
    }
  }

  private Pattern parseAndPattern() {
    int start = startOffset();
    Pattern left = parseNotPattern();
    while (atContextual("and")) {
      advance();
      Pattern right = parseNotPattern();
      left = new Pattern.And(left, right, spanFrom(start));
    }
    return left;
  }

  private Pattern parseNotPattern() {
    int start = startOffset();
    if (atContextual("not") && !peek(1).is(ARROW) && !peek(1).is(COLON) && !peek(1).is(COMMA)) {
      advance();
      Pattern p = parseNotPattern();
      return new Pattern.Not(p, spanFrom(start));
    }
    return parsePrimaryPattern();
  }

  private static final Set<String> PATTERN_WORDS = Set.of("and", "or", "when", "not");

  private boolean isBindingName(Token t) {
    return t.is(IDENTIFIER) && (t.escaped() || !PATTERN_WORDS.contains(t.text()));
  }

  private Pattern parsePrimaryPattern() {
    enter();
    try {
      int start = startOffset();
      Token t = tok();
      switch (t.kind()) {
        case LPAREN -> {
          return parseRecursivePattern(null, start);
        }
        case LBRACE -> {
          return parseRecursivePattern(null, start);
        }
        case LBRACKET -> {
          return parseListPattern();
        }
        case DOTDOT -> {
          advance();
          Pattern sub = null;
          if (!at(COMMA) && !at(RBRACKET)) {
            sub = parsePattern();
          }
          return new Pattern.Slice(sub, spanFrom(start));
        }
        case LT, LE, GT -> {
          BinaryOp op;
          if (t.is(LT)) {
            op = BinaryOp.LT;
            advance();
          } else if (t.is(LE)) {
            op = BinaryOp.LE;
            advance();
          } else if (kind(pos + 1) == EQ && adjacent(pos)) {
            op = BinaryOp.GE;
            advance();
            advance();
          } else {
            op = BinaryOp.GT;
            advance();
          }
          Expr value = parseBinary(P_SHIFT);
          return new Pattern.Relational(op, value, spanFrom(start));
        }
        case EQEQ, BANG_EQ -> {
          advance();
          errorAt(
              Code.EXPECTED_PATTERN,
              t.span(),
              "'" + t.text() + "' is not a pattern operator",
              t.is(EQEQ)
                  ? "write the constant directly, e.g. 'is 5'"
                  : "use 'not', e.g. 'is not 5'");
          Expr value = parseBinary(P_SHIFT);
          return new Pattern.Constant(value, spanFrom(start));
        }
        default -> {}
      }
      if (t.isContextual("var") && (peek(1).is(IDENTIFIER) || peek(1).is(LPAREN))) {
        advance();
        if (at(LPAREN)) {
          return parseVarDesignation(start);
        }
        Token n = advance();
        return new Pattern.Var(n.text(), spanFrom(start));
      }
      if (t.isContextual("_")) {
        advance();
        return new Pattern.Discard(t.span());
      }
      if (t.is(IDENTIFIER) || isPrimitiveKind(t.kind())) {
        int j = scanType(pos, false);
        if (j > 0) {
          TokenKind next = kind(j);
          boolean simpleName = true;
          for (int i = pos; i < j; i++) {
            TokenKind k = kind(i);
            if (k != IDENTIFIER && k != DOT) {
              simpleName = false;
            }
          }
          if (next == LPAREN || next == LBRACE) {
            TypeNode type = parseType(false);
            return parseRecursivePattern(type, start);
          }
          if (isBindingName(tokAt(j))) {
            TypeNode type = parseType(false);
            Token n = advance();
            return new Pattern.Type(type, n.text(), spanFrom(start));
          }
          if (!simpleName) {
            TypeNode type = parseType(false);
            return new Pattern.Type(type, null, spanFrom(start));
          }
        }
      }
      if (canStartExpression(t)) {
        Expr value = parseBinary(P_SHIFT);
        return new Pattern.Constant(value, spanFrom(start));
      }
      errorAt(Code.EXPECTED_PATTERN, t.span(), "expected pattern, found " + t.describe(), null);
      return new Pattern.Discard(new Span(start, start));
    } finally {
      exit();
    }
  }

  /** {@code var (a, (b, c))} after {@code var}. */
  private Pattern parseVarDesignation(int start) {
    expect(LPAREN);
    List<Pattern> elems = new ArrayList<>();
    while (!at(RPAREN) && !atEof()) {
      int es = startOffset();
      int before = pos;
      if (at(LPAREN)) {
        elems.add(parseVarDesignation(es));
      } else if (atContextual("_")) {
        elems.add(new Pattern.Discard(advance().span()));
      } else {
        String n = expectIdent("variable name");
        elems.add(new Pattern.Var(n, spanFrom(es)));
      }
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RPAREN);
    return new Pattern.Recursive(null, elems, null, null, spanFrom(start));
  }

  private Pattern parseRecursivePattern(TypeNode type, int start) {
    List<Pattern> positional = null;
    List<Pattern.PropertySub> props = null;
    boolean trailingComma = false;
    if (at(LPAREN)) {
      advance();
      positional = new ArrayList<>();
      while (!at(RPAREN) && !atEof()) {
        int before = pos;
        if (at(IDENTIFIER) && peek(1).is(COLON)) {
          // Named positional subpattern `name: pattern`: the name is documentation only.
          advance();
          advance();
        }
        positional.add(parsePattern());
        trailingComma = false;
        if (!accept(COMMA) || pos == before) {
          break;
        }
        trailingComma = true;
      }
      expect(RPAREN);
    }
    if (at(LBRACE)) {
      advance();
      props = new ArrayList<>();
      while (!at(RBRACE) && !atEof()) {
        int ps = startOffset();
        int before = pos;
        List<String> path = new ArrayList<>();
        path.add(expectIdent("property name"));
        while (accept(DOT)) {
          path.add(expectIdent("property name"));
        }
        Span pathSpan = spanFrom(ps);
        expect(COLON);
        Pattern sub = parsePattern();
        props.add(new Pattern.PropertySub(path, pathSpan, sub));
        if (!accept(COMMA) || pos == before) {
          break;
        }
      }
      expect(RBRACE);
    }
    String binding = null;
    if (isBindingName(tok())) {
      binding = advance().text();
    }
    if (type == null
        && positional != null
        && positional.size() == 1
        && !trailingComma
        && props == null
        && binding == null) {
      return new Pattern.Paren(positional.getFirst(), spanFrom(start));
    }
    return new Pattern.Recursive(type, positional, props, binding, spanFrom(start));
  }

  private Pattern parseListPattern() {
    int start = startOffset();
    expect(LBRACKET);
    List<Pattern> elems = new ArrayList<>();
    while (!at(RBRACKET) && !atEof()) {
      int before = pos;
      elems.add(parsePattern());
      if (!accept(COMMA) || pos == before) {
        break;
      }
    }
    expect(RBRACKET);
    String binding = isBindingName(tok()) ? advance().text() : null;
    return new Pattern.ListPattern(elems, binding, spanFrom(start));
  }
}
