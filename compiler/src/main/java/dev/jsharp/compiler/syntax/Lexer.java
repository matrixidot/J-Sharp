package dev.jsharp.compiler.syntax;

import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.diag.Diagnostic;
import dev.jsharp.compiler.diag.DiagnosticSink;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.source.Span;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written, error-tolerant lexer. Never throws on bad input: problems are reported to the sink
 * and lexing continues with a best-effort token.
 *
 * <p>Interpolated strings are lexed as a single {@link TokenKind#INTERP_STRING} token whose value
 * is a list of {@link InterpPiece}s; hole expressions are re-lexed by the parser using {@link
 * #Lexer(SourceFile, int, int, DiagnosticSink)} so that all offsets stay absolute.
 */
public final class Lexer {
  private final SourceFile file;
  private final String src;
  private final int limit;
  private final DiagnosticSink sink;
  private int pos;
  private int errorsReported;

  public Lexer(SourceFile file, DiagnosticSink sink) {
    this(file, 0, file.content().length(), sink);
  }

  /** Lexes only the range {@code [start, end)} of {@code file}. */
  public Lexer(SourceFile file, int start, int end, DiagnosticSink sink) {
    this.file = file;
    this.src = file.content();
    this.pos = start;
    this.limit = end;
    this.sink = sink;
  }

  /** Tokenizes the whole range; the result always ends with an {@link TokenKind#EOF} token. */
  public static List<Token> tokenize(SourceFile file, DiagnosticSink sink) {
    return new Lexer(file, sink).tokenize();
  }

  public List<Token> tokenize() {
    List<Token> out = new ArrayList<>();
    while (true) {
      Token t = next();
      out.add(t);
      if (t.kind() == TokenKind.EOF) {
        return out;
      }
    }
  }

  private void error(Code code, int start, int end, String message) {
    errorsReported++;
    sink.report(
        Diagnostic.error(code, file, new Span(start, Math.max(start, end)), message).build());
  }

  private char peek(int ahead) {
    int i = pos + ahead;
    return i < limit ? src.charAt(i) : '\0';
  }

  private boolean atEnd() {
    return pos >= limit;
  }

  private Token make(TokenKind kind, int start) {
    return new Token(kind, start, pos, src.substring(start, pos), null, false);
  }

  private Token make(TokenKind kind, int start, Object value) {
    return new Token(kind, start, pos, src.substring(start, pos), value, false);
  }

  /** Returns the next token, skipping whitespace and comments. */
  public Token next() {
    skipTrivia();
    int before = errorsReported;
    Token t = scan();
    return errorsReported > before && t.kind() != TokenKind.EOF ? t.asMalformed() : t;
  }

  private Token scan() {
    if (atEnd()) {
      return new Token(TokenKind.EOF, limit, limit, "", null, false);
    }
    int start = pos;
    char c = src.charAt(pos);
    if (c == '"') {
      return string(start);
    }
    if (c == '$' && peek(1) == '"') {
      return interpolated(start);
    }
    if (c == '\'') {
      return charLiteral(start);
    }
    if (c >= '0' && c <= '9') {
      return number(start);
    }
    if (c == '`') {
      return escapedIdentifier(start);
    }
    int cp = src.codePointAt(pos);
    if (Character.isJavaIdentifierStart(cp) && c != '$') {
      while (!atEnd() && isIdentPart(src.codePointAt(pos))) {
        pos += Character.charCount(src.codePointAt(pos));
      }
      String text = src.substring(start, pos);
      TokenKind kw = TokenKind.keyword(text);
      return new Token(kw != null ? kw : TokenKind.IDENTIFIER, start, pos, text, null, false);
    }
    return operator(start, c);
  }

  private static boolean isIdentPart(int cp) {
    return Character.isJavaIdentifierPart(cp) && cp != '$' && !Character.isIdentifierIgnorable(cp);
  }

  private void skipTrivia() {
    while (!atEnd()) {
      char c = src.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '﻿') {
        pos++;
      } else if (c == '/' && peek(1) == '/') {
        while (!atEnd() && src.charAt(pos) != '\n') {
          pos++;
        }
      } else if (c == '/' && peek(1) == '*') {
        int start = pos;
        pos += 2;
        while (!atEnd() && !(src.charAt(pos) == '*' && peek(1) == '/')) {
          pos++;
        }
        if (atEnd()) {
          error(Code.UNTERMINATED_COMMENT, start, start + 2, "unterminated block comment");
        } else {
          pos += 2;
        }
      } else {
        return;
      }
    }
  }

  private Token escapedIdentifier(int start) {
    pos++;
    int nameStart = pos;
    while (!atEnd() && src.charAt(pos) != '`' && src.charAt(pos) != '\n') {
      pos++;
    }
    String name = src.substring(nameStart, pos);
    if (atEnd() || src.charAt(pos) != '`' || name.isEmpty()) {
      error(Code.UNEXPECTED_CHARACTER, start, pos, "unterminated or empty escaped identifier");
      return new Token(TokenKind.ERROR, start, pos, src.substring(start, pos), null, false);
    }
    pos++;
    return new Token(TokenKind.IDENTIFIER, start, pos, name, null, true);
  }

  private Token operator(int start, char c) {
    pos++;
    TokenKind k =
        switch (c) {
          case '(' -> TokenKind.LPAREN;
          case ')' -> TokenKind.RPAREN;
          case '{' -> TokenKind.LBRACE;
          case '}' -> TokenKind.RBRACE;
          case '[' -> TokenKind.LBRACKET;
          case ']' -> TokenKind.RBRACKET;
          case ';' -> TokenKind.SEMI;
          case ',' -> TokenKind.COMMA;
          case '@' -> TokenKind.AT;
          case '~' -> TokenKind.TILDE;
          case '>' -> TokenKind.GT;
          case '.' -> match('.') ? TokenKind.DOTDOT : TokenKind.DOT;
          case ':' -> match(':') ? TokenKind.COLONCOLON : TokenKind.COLON;
          case '?' -> {
            if (peek(0) == '.' && !(peek(1) >= '0' && peek(1) <= '9')) {
              pos++;
              yield TokenKind.QUESTION_DOT;
            }
            if (match('?')) {
              yield match('=') ? TokenKind.QUESTION_QUESTION_EQ : TokenKind.QUESTION_QUESTION;
            }
            yield TokenKind.QUESTION;
          }
          case '=' -> {
            if (match('>')) {
              yield TokenKind.ARROW;
            }
            if (match('=')) {
              yield match('=') ? TokenKind.EQEQEQ : TokenKind.EQEQ;
            }
            yield TokenKind.EQ;
          }
          case '!' -> {
            if (match('=')) {
              yield match('=') ? TokenKind.BANG_EQEQ : TokenKind.BANG_EQ;
            }
            yield TokenKind.BANG;
          }
          case '<' -> {
            if (match('<')) {
              yield match('=') ? TokenKind.LTLT_EQ : TokenKind.LTLT;
            }
            yield match('=') ? TokenKind.LE : TokenKind.LT;
          }
          case '+' ->
              match('+') ? TokenKind.PLUSPLUS : match('=') ? TokenKind.PLUS_EQ : TokenKind.PLUS;
          case '-' ->
              match('-') ? TokenKind.MINUSMINUS : match('=') ? TokenKind.MINUS_EQ : TokenKind.MINUS;
          case '*' -> match('=') ? TokenKind.STAR_EQ : TokenKind.STAR;
          case '/' -> match('=') ? TokenKind.SLASH_EQ : TokenKind.SLASH;
          case '%' -> match('=') ? TokenKind.PERCENT_EQ : TokenKind.PERCENT;
          case '^' -> match('=') ? TokenKind.CARET_EQ : TokenKind.CARET;
          case '&' -> match('&') ? TokenKind.AMPAMP : match('=') ? TokenKind.AMP_EQ : TokenKind.AMP;
          case '|' -> match('|') ? TokenKind.BARBAR : match('=') ? TokenKind.BAR_EQ : TokenKind.BAR;
          default -> null;
        };
    if (k != null) {
      return make(k, start);
    }
    // Unknown character: consume the whole code point.
    pos = start + Character.charCount(src.codePointAt(start));
    String shown = src.substring(start, pos);
    error(
        Code.UNEXPECTED_CHARACTER,
        start,
        pos,
        "unexpected character '"
            + (Character.isISOControl(c) ? String.format("\\u%04x", (int) c) : shown)
            + "'");
    return make(TokenKind.ERROR, start);
  }

  private boolean match(char expected) {
    if (!atEnd() && src.charAt(pos) == expected) {
      pos++;
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------- numbers

  private Token number(int start) {
    char c0 = src.charAt(pos);
    if (c0 == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
      pos += 2;
      return radixNumber(start, 16);
    }
    if (c0 == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
      pos += 2;
      return radixNumber(start, 2);
    }
    boolean ok = digits(10);
    boolean isFloat = false;
    if (peek(0) == '.' && isDigit(peek(1), 10)) {
      isFloat = true;
      pos++;
      ok &= digits(10);
    }
    if (peek(0) == 'e' || peek(0) == 'E') {
      int save = pos;
      pos++;
      if (peek(0) == '+' || peek(0) == '-') {
        pos++;
      }
      if (isDigit(peek(0), 10)) {
        isFloat = true;
        ok &= digits(10);
      } else {
        pos = save;
      }
    }
    char suffix = peek(0);
    TokenKind kind;
    if (suffix == 'f' || suffix == 'F') {
      pos++;
      kind = TokenKind.FLOAT_LITERAL;
    } else if (suffix == 'd' || suffix == 'D') {
      pos++;
      kind = TokenKind.DOUBLE_LITERAL;
    } else if ((suffix == 'L' || suffix == 'l') && !isFloat) {
      pos++;
      kind = TokenKind.LONG_LITERAL;
    } else {
      kind = isFloat ? TokenKind.DOUBLE_LITERAL : TokenKind.INT_LITERAL;
    }
    if (trailingIdentChars(start)) {
      return make(
          kind, start, kind == TokenKind.INT_LITERAL || kind == TokenKind.LONG_LITERAL ? 0L : 0.0);
    }
    String text = src.substring(start, pos);
    String clean = text.replace("_", "");
    if (!ok) {
      error(Code.MALFORMED_NUMBER, start, pos, "misplaced '_' in numeric literal");
    }
    if (kind == TokenKind.FLOAT_LITERAL || kind == TokenKind.DOUBLE_LITERAL) {
      String body = stripSuffix(clean);
      try {
        if (kind == TokenKind.FLOAT_LITERAL) {
          float f = Float.parseFloat(body);
          if (Float.isInfinite(f)) {
            error(Code.NUMBER_TOO_LARGE, start, pos, "float literal is too large");
          }
          return make(kind, start, f);
        }
        double d = Double.parseDouble(body);
        if (Double.isInfinite(d)) {
          error(Code.NUMBER_TOO_LARGE, start, pos, "double literal is too large");
        }
        return make(kind, start, d);
      } catch (NumberFormatException e) {
        error(Code.MALFORMED_NUMBER, start, pos, "malformed floating-point literal");
        return make(kind, start, 0.0);
      }
    }
    String digits = stripSuffix(clean);
    if (digits.length() > 1 && digits.charAt(0) == '0') {
      error(Code.LEADING_ZERO, start, pos, "leading zeros are not allowed in decimal literals");
      // fall through: value parsed as decimal
    }
    // Decimal integers: store the magnitude; range is checked by the type checker, which knows
    // whether the literal is the operand of unary minus (e.g. -2147483648).
    try {
      java.math.BigInteger v = new java.math.BigInteger(digits);
      if (v.bitLength() > 64) {
        error(Code.NUMBER_TOO_LARGE, start, pos, "integer literal is too large");
        return make(kind, start, 0L);
      }
      return make(kind, start, v.longValue());
    } catch (NumberFormatException e) {
      error(Code.MALFORMED_NUMBER, start, pos, "malformed integer literal");
      return make(kind, start, 0L);
    }
  }

  private static String stripSuffix(String s) {
    char last = s.charAt(s.length() - 1);
    return switch (last) {
      case 'f', 'F', 'd', 'D', 'l', 'L' -> s.substring(0, s.length() - 1);
      default -> s;
    };
  }

  private Token radixNumber(int start, int radix) {
    int digitsStart = pos;
    boolean ok = digits(radix);
    if (pos == digitsStart) {
      ok = false;
    }
    boolean isLong = false;
    if (peek(0) == 'L' || peek(0) == 'l') {
      pos++;
      isLong = true;
    }
    TokenKind kind = isLong ? TokenKind.LONG_LITERAL : TokenKind.INT_LITERAL;
    if (trailingIdentChars(start)) {
      return make(kind, start, 0L);
    }
    if (!ok) {
      error(
          Code.MALFORMED_NUMBER,
          start,
          pos,
          "malformed " + (radix == 16 ? "hexadecimal" : "binary") + " literal");
      return make(kind, start, 0L);
    }
    String digits = src.substring(digitsStart, isLong ? pos - 1 : pos).replace("_", "");
    java.math.BigInteger v = new java.math.BigInteger(digits, radix);
    int maxBits = isLong ? 64 : 32;
    if (v.bitLength() > maxBits) {
      error(
          Code.NUMBER_TOO_LARGE,
          start,
          pos,
          (isLong ? "long" : "int") + " literal does not fit in " + maxBits + " bits");
      return make(kind, start, 0L);
    }
    long value = isLong ? v.longValue() : (long) v.intValue();
    return make(kind, start, value);
  }

  /** Scans digits and underscores; returns false if underscores are misplaced. */
  private boolean digits(int radix) {
    int start = pos;
    while (!atEnd() && (isDigit(src.charAt(pos), radix) || src.charAt(pos) == '_')) {
      pos++;
    }
    if (pos == start) {
      return true;
    }
    return src.charAt(start) != '_' && src.charAt(pos - 1) != '_';
  }

  private static boolean isDigit(char c, int radix) {
    return Character.digit(c, radix) >= 0 && c < 128;
  }

  private boolean trailingIdentChars(int start) {
    if (!atEnd() && isIdentPart(src.codePointAt(pos))) {
      while (!atEnd() && isIdentPart(src.codePointAt(pos))) {
        pos += Character.charCount(src.codePointAt(pos));
      }
      error(
          Code.MALFORMED_NUMBER,
          start,
          pos,
          "malformed numeric literal '" + src.substring(start, pos) + "'");
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------- chars & strings

  private Token charLiteral(int start) {
    pos++;
    if (atEnd() || src.charAt(pos) == '\n') {
      error(Code.UNTERMINATED_CHAR, start, pos, "unterminated character literal");
      return make(TokenKind.CHAR_LITERAL, start, '\0');
    }
    if (src.charAt(pos) == '\'') {
      pos++;
      error(Code.INVALID_CHAR_LITERAL, start, pos, "empty character literal");
      return make(TokenKind.CHAR_LITERAL, start, '\0');
    }
    StringBuilder sb = new StringBuilder();
    while (!atEnd() && src.charAt(pos) != '\'' && src.charAt(pos) != '\n') {
      if (src.charAt(pos) == '\\') {
        escape(sb);
      } else {
        sb.append(src.charAt(pos++));
      }
    }
    if (atEnd() || src.charAt(pos) != '\'') {
      error(Code.UNTERMINATED_CHAR, start, pos, "unterminated character literal");
      return make(TokenKind.CHAR_LITERAL, start, sb.isEmpty() ? '\0' : sb.charAt(0));
    }
    pos++;
    if (sb.length() != 1) {
      error(
          Code.INVALID_CHAR_LITERAL,
          start,
          pos,
          "character literal must contain exactly one UTF-16 character; use a string for '"
              + sb
              + "'");
      return make(TokenKind.CHAR_LITERAL, start, sb.isEmpty() ? '\0' : sb.charAt(0));
    }
    return make(TokenKind.CHAR_LITERAL, start, sb.charAt(0));
  }

  /** Decodes one escape sequence starting at a backslash, appending to {@code sb}. */
  private void escape(StringBuilder sb) {
    int start = pos;
    pos++; // backslash
    if (atEnd()) {
      error(Code.INVALID_ESCAPE, start, pos, "incomplete escape sequence");
      return;
    }
    char c = src.charAt(pos++);
    switch (c) {
      case 'n' -> sb.append('\n');
      case 't' -> sb.append('\t');
      case 'r' -> sb.append('\r');
      case 'b' -> sb.append('\b');
      case 'f' -> sb.append('\f');
      case 's' -> sb.append(' ');
      case '0' -> sb.append('\0');
      case '\\' -> sb.append('\\');
      case '\'' -> sb.append('\'');
      case '"' -> sb.append('"');
      case '$' -> sb.append('$');
      case 'u' -> {
        int hexStart = pos;
        while (pos < limit && pos - hexStart < 4 && isDigit(src.charAt(pos), 16)) {
          pos++;
        }
        if (pos - hexStart != 4) {
          error(Code.INVALID_ESCAPE, start, pos, "\\u escape requires exactly 4 hex digits");
        } else {
          sb.append((char) Integer.parseInt(src.substring(hexStart, pos), 16));
        }
      }
      default -> {
        if (c == '\n') {
          pos--;
        }
        error(Code.INVALID_ESCAPE, start, pos, "invalid escape sequence '\\" + c + "'");
      }
    }
  }

  private boolean startsWith(String s) {
    return src.startsWith(s, pos) && pos + s.length() <= limit;
  }

  private Token string(int start) {
    if (startsWith("\"\"\"")) {
      return rawString(start);
    }
    pos++;
    StringBuilder sb = new StringBuilder();
    while (!atEnd() && src.charAt(pos) != '"' && src.charAt(pos) != '\n') {
      if (src.charAt(pos) == '\\') {
        escape(sb);
      } else {
        sb.append(src.charAt(pos++));
      }
    }
    if (atEnd() || src.charAt(pos) != '"') {
      error(Code.UNTERMINATED_STRING, start, pos, "unterminated string literal");
      return make(TokenKind.STRING_LITERAL, start, sb.toString());
    }
    pos++;
    return make(TokenKind.STRING_LITERAL, start, sb.toString());
  }

  /** Raw string {@code """..."""}: no escapes; multi-line form strips common indentation. */
  private Token rawString(int start) {
    pos += 3;
    int contentStart = pos;
    int close = src.indexOf("\"\"\"", pos);
    if (close < 0 || close + 3 > limit) {
      pos = limit;
      error(Code.UNTERMINATED_STRING, start, start + 3, "unterminated raw string literal");
      return make(TokenKind.STRING_LITERAL, start, src.substring(contentStart, limit));
    }
    pos = close + 3;
    String raw = src.substring(contentStart, close);
    return make(TokenKind.STRING_LITERAL, start, processRaw(raw));
  }

  /** True if the raw content starts with (optional whitespace and) a line break. */
  private static boolean isMultilineRaw(String raw) {
    int i = 0;
    while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) {
      i++;
    }
    return i < raw.length() && (raw.charAt(i) == '\n' || raw.charAt(i) == '\r');
  }

  static String processRaw(String raw) {
    if (!isMultilineRaw(raw)) {
      return raw;
    }
    String normalized = raw.replace("\r\n", "\n");
    String body = normalized.substring(normalized.indexOf('\n') + 1);
    // Java text-block style: common indentation (closing line included) is removed and trailing
    // whitespace is stripped.
    return body.stripIndent();
  }

  // ---------------------------------------------------------------- interpolation

  private Token interpolated(int start) {
    pos++; // '$'
    boolean raw = startsWith("\"\"\"");
    pos += raw ? 3 : 1;
    List<InterpPiece> pieces = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    int contentStart = pos;
    boolean closed = false;
    while (!atEnd()) {
      char c = src.charAt(pos);
      if (raw ? startsWith("\"\"\"") : c == '"') {
        pos += raw ? 3 : 1;
        closed = true;
        break;
      }
      if (!raw && c == '\n') {
        break;
      }
      if (c == '\\' && !raw) {
        escape(text);
      } else if (c == '{' && peek(1) == '{') {
        text.append('{');
        pos += 2;
      } else if (c == '}' && peek(1) == '}') {
        text.append('}');
        pos += 2;
      } else if (c == '}') {
        error(
            Code.INVALID_INTERPOLATION,
            pos,
            pos + 1,
            "unescaped '}' in interpolated string; write '}}'");
        pos++;
      } else if (c == '{') {
        if (!text.isEmpty()) {
          pieces.add(new InterpPiece.Text(text.toString()));
          text.setLength(0);
        }
        InterpPiece.Hole hole = hole(raw);
        if (hole == null) {
          break; // error already reported; string is unterminated
        }
        pieces.add(hole);
      } else {
        text.append(c);
        pos++;
      }
    }
    if (!text.isEmpty()) {
      pieces.add(new InterpPiece.Text(text.toString()));
    }
    if (!closed && !holeFailed) {
      error(Code.UNTERMINATED_STRING, start, pos, "unterminated interpolated string");
    }
    holeFailed = false;
    if (raw && closed) {
      pieces = dedentRawPieces(pieces, src.substring(contentStart, pos - 3));
    }
    return make(TokenKind.INTERP_STRING, start, List.copyOf(pieces));
  }

  /** Applies raw multi-line indentation stripping to the text pieces of {@code $"""...""" }. */
  private static List<InterpPiece> dedentRawPieces(List<InterpPiece> pieces, String rawSource) {
    if (!isMultilineRaw(rawSource)) {
      return pieces;
    }
    String[] lines = rawSource.replace("\r\n", "\n").split("\n", -1);
    int indent = Integer.MAX_VALUE;
    for (int i = 1; i < lines.length; i++) {
      String l = lines[i];
      boolean last = i == lines.length - 1;
      if (l.isBlank() && !last) {
        continue;
      }
      int w = 0;
      while (w < l.length() && (l.charAt(w) == ' ' || l.charAt(w) == '\t')) {
        w++;
      }
      indent = Math.min(indent, w);
    }
    if (indent == Integer.MAX_VALUE) {
      indent = 0;
    }
    List<InterpPiece> out = new ArrayList<>();
    boolean atLineStart = false;
    boolean first = true;
    for (int p = 0; p < pieces.size(); p++) {
      InterpPiece piece = pieces.get(p);
      if (!(piece instanceof InterpPiece.Text(String value))) {
        out.add(piece);
        atLineStart = false;
        first = false;
        continue;
      }
      String v = value.replace("\r\n", "\n");
      if (first) {
        v = v.substring(v.indexOf('\n') + 1);
        atLineStart = true;
      }
      StringBuilder sb = new StringBuilder();
      int i = 0;
      while (i < v.length()) {
        if (atLineStart) {
          int skipped = 0;
          while (i < v.length()
              && skipped < indent
              && (v.charAt(i) == ' ' || v.charAt(i) == '\t')) {
            i++;
            skipped++;
          }
          atLineStart = false;
          continue;
        }
        char ch = v.charAt(i++);
        sb.append(ch);
        if (ch == '\n') {
          atLineStart = true;
        }
      }
      String s = sb.toString();
      if (p == pieces.size() - 1) {
        // Drop the closing delimiter's indentation (whitespace after the last newline), keeping
        // the newline itself, like a plain raw string.
        int nl = s.lastIndexOf('\n');
        if (nl >= 0 && s.substring(nl + 1).isBlank()) {
          s = s.substring(0, nl + 1);
        }
      }
      first = false;
      if (!s.isEmpty()) {
        out.add(new InterpPiece.Text(s));
      }
    }
    return out;
  }

  /**
   * Scans an interpolation hole starting at '{'. Returns {@code null} if the string ended inside
   * the hole.
   */
  private InterpPiece.Hole hole(boolean raw) {
    int open = pos;
    pos++;
    int exprStart = pos;
    int depth = 0;
    while (!atEnd()) {
      char c = src.charAt(pos);
      if (c == '\n' && !raw) {
        return holeFailed(open, raw);
      }
      switch (c) {
        case '(', '[', '{' -> {
          depth++;
          pos++;
        }
        case ')', ']' -> {
          depth = Math.max(0, depth - 1);
          pos++;
        }
        case '}' -> {
          if (depth == 0) {
            int exprEnd = pos;
            pos++;
            checkHoleNotEmpty(open, exprStart, exprEnd);
            return new InterpPiece.Hole(exprStart, exprEnd, null, open, pos);
          }
          depth--;
          pos++;
        }
        case ':' -> {
          if (depth == 0 && peek(1) != ':' && (pos == exprStart || src.charAt(pos - 1) != ':')) {
            int exprEnd = pos;
            pos++;
            int fmtStart = pos;
            while (!atEnd()
                && src.charAt(pos) != '}'
                && src.charAt(pos) != '\n'
                && src.charAt(pos) != '"') {
              pos++;
            }
            if (atEnd() || src.charAt(pos) != '}') {
              return holeFailed(open, raw);
            }
            String fmt = src.substring(fmtStart, pos);
            pos++;
            checkHoleNotEmpty(open, exprStart, exprEnd);
            if (fmt.isEmpty()) {
              error(Code.INVALID_INTERPOLATION, fmtStart - 1, fmtStart, "empty format specifier");
              fmt = null;
            }
            return new InterpPiece.Hole(exprStart, exprEnd, fmt, open, pos);
          }
          pos++;
        }
        case '"', '\'', '$' -> {
          if (c == '$' && peek(1) != '"') {
            pos++;
          } else if (c == '"' && raw && startsWith("\"\"\"")) {
            return holeFailed(open, raw);
          } else {
            // A nested literal inside the hole. Its own diagnostics are discarded here: the parser
            // re-lexes the hole and reports them precisely.
            Lexer nested = new Lexer(file, pos, limit, d -> {});
            Token t = nested.next();
            if (t.malformed()) {
              return holeFailed(open, raw);
            }
            pos = t.end();
          }
        }
        default -> pos++;
      }
    }
    return holeFailed(open, raw);
  }

  /**
   * Reports an unterminated hole and skips to the end of the line (or of the raw string) so the
   * rest of the literal does not produce cascading errors.
   */
  private InterpPiece.Hole holeFailed(int open, boolean raw) {
    error(
        Code.INVALID_INTERPOLATION,
        open,
        open + 1,
        "unterminated interpolation hole; expected '}'");
    if (raw) {
      int close = src.indexOf("\"\"\"", pos);
      pos = close < 0 || close + 3 > limit ? limit : close + 3;
    } else {
      while (!atEnd() && src.charAt(pos) != '\n') {
        pos++;
      }
    }
    holeFailed = true;
    return null;
  }

  private boolean holeFailed;

  private void checkHoleNotEmpty(int open, int exprStart, int exprEnd) {
    if (src.substring(exprStart, exprEnd).isBlank()) {
      error(Code.INVALID_INTERPOLATION, open, exprEnd + 1, "empty interpolation hole");
    }
  }
}
