package io.github.matrixidot.jsharp.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON for the language server: values are {@code null}, {@link Boolean}, {@link Long},
 * {@link Double}, {@link String}, {@code List<Object>} and {@code Map<String, Object>}.
 */
public final class Json {
  private final String text;
  private int pos;

  private Json(String text) {
    this.text = text;
  }

  /** Parses one JSON value; throws {@link IllegalArgumentException} on malformed input. */
  public static Object parse(String text) {
    Json p = new Json(text);
    p.skipWs();
    Object v = p.value();
    p.skipWs();
    if (p.pos != text.length()) {
      throw p.error("trailing characters");
    }
    return v;
  }

  private IllegalArgumentException error(String what) {
    return new IllegalArgumentException("malformed JSON at " + pos + ": " + what);
  }

  private void skipWs() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private Object value() {
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    char c = text.charAt(pos);
    return switch (c) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Object literal(String word, Object value) {
    if (!text.startsWith(word, pos)) {
      throw error("expected " + word);
    }
    pos += word.length();
    return value;
  }

  private Map<String, Object> object() {
    Map<String, Object> out = new LinkedHashMap<>();
    pos++;
    skipWs();
    if (peek() == '}') {
      pos++;
      return out;
    }
    while (true) {
      skipWs();
      if (peek() != '"') {
        throw error("expected a key");
      }
      String key = string();
      skipWs();
      expect(':');
      skipWs();
      out.put(key, value());
      skipWs();
      if (peek() == ',') {
        pos++;
        continue;
      }
      expect('}');
      return out;
    }
  }

  private List<Object> array() {
    List<Object> out = new ArrayList<>();
    pos++;
    skipWs();
    if (peek() == ']') {
      pos++;
      return out;
    }
    while (true) {
      skipWs();
      out.add(value());
      skipWs();
      if (peek() == ',') {
        pos++;
        continue;
      }
      expect(']');
      return out;
    }
  }

  private char peek() {
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    return text.charAt(pos);
  }

  private void expect(char c) {
    if (peek() != c) {
      throw error("expected '" + c + "'");
    }
    pos++;
  }

  private String string() {
    pos++;
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = peek();
      pos++;
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      char e = peek();
      pos++;
      switch (e) {
        case '"', '\\', '/' -> sb.append(e);
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          if (pos + 4 > text.length()) {
            throw error("bad unicode escape");
          }
          sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> throw error("bad escape");
      }
    }
  }

  private Object number() {
    int start = pos;
    while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
      pos++;
    }
    String s = text.substring(start, pos);
    if (s.isEmpty()) {
      throw error("unexpected character");
    }
    try {
      if (s.contains(".") || s.contains("e") || s.contains("E")) {
        return Double.parseDouble(s);
      }
      return Long.parseLong(s);
    } catch (NumberFormatException ex) {
      throw error("bad number " + s);
    }
  }

  /** Serializes a value (maps keep insertion order). */
  public static String write(Object v) {
    StringBuilder sb = new StringBuilder();
    write(v, sb);
    return sb.toString();
  }

  private static void write(Object v, StringBuilder sb) {
    switch (v) {
      case null -> sb.append("null");
      case String s -> quote(s, sb);
      case Boolean b -> sb.append(b);
      case Double d -> sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
      case Number n -> sb.append(n.longValue());
      case Map<?, ?> m -> {
        sb.append('{');
        boolean first = true;
        for (var e : m.entrySet()) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          quote(String.valueOf(e.getKey()), sb);
          sb.append(':');
          write(e.getValue(), sb);
        }
        sb.append('}');
      }
      case List<?> l -> {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
          if (i > 0) {
            sb.append(',');
          }
          write(l.get(i), sb);
        }
        sb.append(']');
      }
      default -> quote(v.toString(), sb);
    }
  }

  private static void quote(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  /** Builds a map from alternating keys and values (insertion order kept). */
  public static Map<String, Object> obj(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
