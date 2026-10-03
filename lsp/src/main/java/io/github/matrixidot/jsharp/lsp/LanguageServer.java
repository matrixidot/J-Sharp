package io.github.matrixidot.jsharp.lsp;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.Note;
import io.github.matrixidot.jsharp.compiler.ide.SourceIndex;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A Language Server Protocol server for J#, speaking JSON-RPC over a byte stream (stdio in
 * practice). Supports full-document sync, diagnostics, hover, go-to-definition, document symbols
 * and completion. Every edit re-analyzes the document's unit with the real compiler, so editor
 * errors match {@code jsharp check} exactly.
 */
public final class LanguageServer {
  private static final String COMPLETION_MARKER = "__jsharpComplete";

  private static final List<String> KEYWORDS =
      List.of(
          "var",
          "val",
          "if",
          "else",
          "for",
          "foreach",
          "in",
          "while",
          "do",
          "switch",
          "case",
          "default",
          "return",
          "break",
          "continue",
          "throw",
          "try",
          "catch",
          "finally",
          "using",
          "class",
          "interface",
          "record",
          "enum",
          "sealed",
          "open",
          "abstract",
          "override",
          "public",
          "private",
          "protected",
          "internal",
          "static",
          "final",
          "async",
          "await",
          "new",
          "this",
          "super",
          "null",
          "true",
          "false",
          "is",
          "as",
          "when",
          "with",
          "import",
          "package",
          "typeof",
          "nameof",
          "checked",
          "get",
          "set",
          "init",
          "required");

  private final Transport transport;
  private final PrintStream log;
  private final List<Path> classPath;
  private Workspace workspace;
  private boolean shutdown;

  public LanguageServer(InputStream in, OutputStream out, PrintStream log, List<Path> classPath) {
    this.transport = new Transport(in, out);
    this.log = log;
    this.classPath = classPath;
  }

  /** Serves until {@code exit} or end of input; returns the process exit code. */
  public int run() throws IOException {
    while (true) {
      String body = transport.read();
      if (body == null) {
        return shutdown ? 0 : 1;
      }
      Map<String, Object> msg;
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse(body);
        msg = m;
      } catch (RuntimeException e) {
        respondError(null, -32700, "parse error: " + e.getMessage());
        continue;
      }
      String method = (String) msg.get("method");
      Object id = msg.get("id");
      if ("exit".equals(method)) {
        return shutdown ? 0 : 1;
      }
      try {
        Object result = handle(method, params(msg));
        if (id != null) {
          respond(id, result);
        }
      } catch (UnsupportedOperationException e) {
        if (id != null) {
          respondError(id, -32601, "method not found: " + method);
        }
      } catch (RuntimeException e) {
        log.println("[jsharp-lsp] error handling " + method + ": " + e);
        if (Boolean.getBoolean("jsharp.debug")) {
          e.printStackTrace(log);
        }
        if (id != null) {
          respondError(id, -32603, "internal error: " + e);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> params(Map<String, Object> msg) {
    Object p = msg.get("params");
    return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private Object handle(String method, Map<String, Object> p) throws IOException {
    if (method == null) {
      return null; // a response to one of our requests: ignore
    }
    return switch (method) {
      case "initialize" -> initialize(p);
      case "initialized",
          "$/cancelRequest",
          "$/setTrace",
          "workspace/didChangeConfiguration",
          "workspace/didChangeWatchedFiles",
          "textDocument/didSave" -> {
        if (method.equals("textDocument/didSave") && workspace != null) {
          workspace.change(uri(p), workspace.text(uri(p)));
          publishAll();
        }
        yield null;
      }
      case "shutdown" -> {
        shutdown = true;
        yield null;
      }
      case "textDocument/didOpen" -> {
        Map<String, Object> doc = map(p, "textDocument");
        workspace().open(URI.create((String) doc.get("uri")), (String) doc.get("text"));
        publishAll();
        yield null;
      }
      case "textDocument/didChange" -> {
        List<Object> changes = list(p, "contentChanges");
        if (!changes.isEmpty()) {
          Map<?, ?> last = (Map<?, ?>) changes.getLast();
          workspace().change(uri(p), (String) last.get("text"));
          publishAll();
        }
        yield null;
      }
      case "textDocument/didClose" -> {
        URI u = uri(p);
        workspace().close(u);
        notify(
            "textDocument/publishDiagnostics",
            Json.obj("uri", u.toString(), "diagnostics", List.of()));
        yield null;
      }
      case "textDocument/hover" -> hover(uri(p), map(p, "position"));
      case "textDocument/definition" -> definition(uri(p), map(p, "position"));
      case "textDocument/documentSymbol" -> documentSymbols(uri(p));
      case "textDocument/completion" -> completion(uri(p), map(p, "position"));
      default -> {
        if (method.startsWith("$/")) {
          yield null; // optional notifications may be ignored
        }
        throw new UnsupportedOperationException(method);
      }
    };
  }

  private Workspace workspace() {
    if (workspace == null) {
      workspace = new Workspace(classPath);
    }
    return workspace;
  }

  private Object initialize(Map<String, Object> p) {
    workspace();
    Map<String, Object> capabilities =
        Json.obj(
            "textDocumentSync", Json.obj("openClose", true, "change", 1, "save", true),
            "hoverProvider", true,
            "definitionProvider", true,
            "documentSymbolProvider", true,
            "completionProvider", Json.obj("triggerCharacters", List.of(".")));
    return Json.obj(
        "capabilities",
        capabilities,
        "serverInfo",
        Json.obj("name", LanguageInfo.ID + "-lsp", "version", LanguageInfo.VERSION));
  }

  // ------------------------------------------------------------------ diagnostics

  /** Re-analyzes and publishes diagnostics for every open document. */
  private void publishAll() throws IOException {
    for (URI u : List.copyOf(workspace().openDocuments())) {
      Workspace.Unit unit = workspace().unit(u);
      SourceFile f = unit.file(u);
      List<Object> out = new ArrayList<>();
      for (Diagnostic d : unit.comp.diagnostics().sorted()) {
        if (d.file() == f) {
          out.add(diagnostic(d, f.content()));
        }
      }
      notify("textDocument/publishDiagnostics", Json.obj("uri", u.toString(), "diagnostics", out));
    }
  }

  private static Map<String, Object> diagnostic(Diagnostic d, String text) {
    int start = d.span() == null ? 0 : d.span().start();
    int end = d.span() == null ? 0 : Math.max(d.span().end(), start + 1);
    StringBuilder message = new StringBuilder(d.message());
    for (Note n : d.notes()) {
      if (n.span() == null) {
        message.append("\nnote: ").append(n.message());
      }
    }
    if (d.help() != null) {
      message.append("\nhelp: ").append(d.help());
    }
    int severity =
        switch (d.severity()) {
          case ERROR -> 1;
          case WARNING -> 2;
          case INFO -> 3;
        };
    return Json.obj(
        "range", Workspace.range(text, start, Math.min(end, Math.max(start, text.length()))),
        "severity", severity,
        "code", d.code().id(),
        "source", LanguageInfo.ID,
        "message", message.toString());
  }

  // ------------------------------------------------------------------ hover & definition

  private Object hover(URI uri, Map<String, Object> pos) {
    Workspace.Unit unit = workspace().unit(uri);
    SourceFile f = unit.file(uri);
    int offset = offset(f, pos);
    Optional<SourceIndex.Ref> ref = unit.comp.index().at(f, offset);
    if (ref.isEmpty()) {
      return null;
    }
    SourceIndex.Ref r = ref.get();
    String text = describe(r);
    if (text == null) {
      return null;
    }
    return Json.obj(
        "contents", Json.obj("kind", "markdown", "value", "```jsharp\n" + text + "\n```"),
        "range", Workspace.range(f.content(), r.span().start(), r.span().end()));
  }

  /** A J#-style one-line description of what a reference denotes. */
  static String describe(SourceIndex.Ref r) {
    return switch (r.symbol()) {
      case null -> r.type() == null ? null : r.type().display();
      case VarSymbol v -> (v.has(Flags.FINAL) ? "val " : "var ") + v.name() + ": " + show(v.type());
      case FieldSymbol fs -> modifiers(fs) + show(fs.type()) + " " + owner(fs.owner()) + fs.name();
      case PropertySymbol ps ->
          show(ps.type()) + " " + owner(ps.owner()) + ps.name() + accessors(ps);
      case MethodSymbol m -> signature(m);
      case ClassSymbol c -> classHeader(c);
      default -> r.symbol().name();
    };
  }

  private static String show(Type t) {
    return t == null ? "?" : t.display();
  }

  private static String modifiers(Symbol s) {
    return s.isStatic() ? "static " : "";
  }

  private static String owner(ClassSymbol c) {
    return c.has(Flags.MODULE) ? "" : c.displayName() + ".";
  }

  private static String accessors(PropertySymbol p) {
    if (p.setter() == null) {
      return " { get; }";
    }
    return p.isInitOnly() ? " { get; init; }" : " { get; set; }";
  }

  static String signature(MethodSymbol m) {
    StringBuilder sb = new StringBuilder();
    if (m.isStatic() && !m.owner().has(Flags.MODULE)) {
      sb.append("static ");
    }
    if (!m.isConstructor()) {
      sb.append(show(m.returnType())).append(' ');
    }
    if (m.isConstructor()) {
      sb.append("new ").append(m.owner().displayName());
    } else {
      sb.append(owner(m.owner())).append(m.name());
    }
    if (!m.typeParams().isEmpty()) {
      sb.append('<');
      for (int i = 0; i < m.typeParams().size(); i++) {
        sb.append(i > 0 ? ", " : "").append(m.typeParams().get(i).name());
      }
      sb.append('>');
    }
    sb.append('(');
    for (int i = 0; i < m.params().size(); i++) {
      MethodSymbol.Param p = m.params().get(i);
      if (i > 0) {
        sb.append(", ");
      }
      if (i == 0 && m.isExtension()) {
        sb.append("this ");
      }
      sb.append(show(p.type())).append(' ').append(p.name());
      if (p.hasDefault()) {
        sb.append(" = ")
            .append(
                p.defaultValue() instanceof String s
                    ? "\"" + s + "\""
                    : String.valueOf(p.defaultValue()));
      }
    }
    return sb.append(')').toString();
  }

  private static String classHeader(ClassSymbol c) {
    String kind =
        c.isInterface() ? "interface" : c.isEnum() ? "enum" : c.isRecord() ? "record" : "class";
    StringBuilder sb = new StringBuilder();
    if (c.has(Flags.SEALED)) {
      sb.append("sealed ");
    } else if (!c.isInterface() && !c.isFinal() && !c.isEnum() && !c.isRecord()) {
      sb.append(c.isAbstract() ? "abstract " : "open ");
    }
    sb.append(kind).append(' ').append(c.qualifiedName());
    return sb.toString();
  }

  private Object definition(URI uri, Map<String, Object> pos) {
    Workspace.Unit unit = workspace().unit(uri);
    SourceFile f = unit.file(uri);
    Optional<SourceIndex.Ref> ref = unit.comp.index().at(f, offset(f, pos));
    if (ref.isEmpty() || ref.get().symbol() == null) {
      return null;
    }
    Optional<SourceIndex.Location> loc = unit.comp.index().declaration(ref.get().symbol());
    if (loc.isEmpty()) {
      return null;
    }
    URI target = unit.uris.get(loc.get().file());
    if (target == null) {
      return null;
    }
    String text = loc.get().file().content();
    return Json.obj(
        "uri", target.toString(),
        "range", Workspace.range(text, loc.get().span().start(), loc.get().span().end()));
  }

  // ------------------------------------------------------------------ symbols

  private Object documentSymbols(URI uri) {
    Workspace.Unit unit = workspace().unit(uri);
    SourceFile f = unit.file(uri);
    List<Object> out = new ArrayList<>();
    for (SourceIndex.Ref r : unit.comp.index().refs(f)) {
      if (!r.declaration() || r.symbol() == null || r.kind() == SourceIndex.Kind.LOCAL) {
        continue;
      }
      int kind =
          switch (r.symbol()) {
            case ClassSymbol c -> c.isInterface() ? 11 : c.isEnum() ? 10 : c.isRecord() ? 23 : 5;
            case MethodSymbol m -> m.owner().has(Flags.MODULE) ? 12 : 6;
            case PropertySymbol p -> 7;
            case FieldSymbol fs -> fs.has(Flags.ENUM_CONSTANT) ? 22 : 8;
            default -> 13;
          };
      String container =
          switch (r.symbol()) {
            case ClassSymbol c -> c.outer() == null ? "" : c.outer().name();
            case MethodSymbol m -> m.owner().has(Flags.MODULE) ? "" : m.owner().name();
            case PropertySymbol p -> p.owner().name();
            case FieldSymbol fs -> fs.owner().has(Flags.MODULE) ? "" : fs.owner().name();
            default -> "";
          };
      out.add(
          Json.obj(
              "name",
              r.symbol().name(),
              "kind",
              kind,
              "containerName",
              container,
              "location",
              Json.obj(
                  "uri", uri.toString(),
                  "range", Workspace.range(f.content(), r.span().start(), r.span().end()))));
    }
    return out;
  }

  // ------------------------------------------------------------------ completion

  private Object completion(URI uri, Map<String, Object> pos) {
    String text = workspace().text(uri);
    int offset = Workspace.offset(text, num(pos, "line"), num(pos, "character"));
    int wordStart = offset;
    while (wordStart > 0 && Character.isJavaIdentifierPart(text.charAt(wordStart - 1))) {
      wordStart--;
    }
    String prefix = text.substring(wordStart, offset);
    boolean member = wordStart > 0 && text.charAt(wordStart - 1) == '.';
    Map<String, Map<String, Object>> items = new LinkedHashMap<>();
    if (member) {
      // Make the expression complete (receiver.__marker) so the receiver gets a type; if the line
      // is unfinished (no ';' yet), terminate the statement too.
      for (String patch : List.of(COMPLETION_MARKER, COMPLETION_MARKER + ";")) {
        String patched = text.substring(0, wordStart) + patch + text.substring(offset);
        Workspace.Unit unit = workspace().analyzePatched(uri, patched);
        SourceFile f = unit.file(uri);
        Optional<SourceIndex.Ref> recv = unit.comp.index().endingAt(f, wordStart - 1);
        if (recv.isPresent() && recv.get().type() != null) {
          memberItems(unit, recv.get().type(), items);
          break;
        }
      }
    } else {
      Workspace.Unit unit = workspace().unit(uri);
      SourceFile f = unit.file(uri);
      for (SourceIndex.Ref r : unit.comp.index().refs(f)) {
        if (r.declaration() && r.symbol() instanceof VarSymbol v && r.span().start() < offset) {
          items.putIfAbsent(v.name(), item(v.name(), 6, show(v.type())));
        }
      }
      for (SourceFile other : unit.uris.keySet()) {
        for (SourceIndex.Ref r : unit.comp.index().refs(other)) {
          if (!r.declaration()) {
            continue;
          }
          switch (r.symbol()) {
            case ClassSymbol c -> items.putIfAbsent(c.name(), item(c.name(), 7, classHeader(c)));
            case MethodSymbol m when m.owner().has(Flags.MODULE) ->
                items.putIfAbsent(m.name(), item(m.name(), 3, signature(m)));
            default -> {}
          }
        }
      }
      ClassSymbol prelude = unit.comp.lookupClass("jsharp/core/Prelude");
      if (prelude != null) {
        for (MethodSymbol m : prelude.allMethods()) {
          if (m.has(Flags.PUBLIC) && m.isStatic()) {
            items.putIfAbsent(m.name(), item(m.name(), 3, signature(m)));
          }
        }
      }
      for (String k : KEYWORDS) {
        items.putIfAbsent(k, item(k, 14, null));
      }
    }
    List<Object> out = new ArrayList<>();
    for (var e : items.entrySet()) {
      if (e.getKey().startsWith(prefix)) {
        out.add(e.getValue());
      }
    }
    return Json.obj("isIncomplete", false, "items", out);
  }

  private static Map<String, Object> item(String label, int kind, String detail) {
    Map<String, Object> m = Json.obj("label", label, "kind", kind);
    if (detail != null) {
      m.put("detail", detail);
    }
    return m;
  }

  /** Members of {@code type} (and its supertypes) plus applicable stdlib extensions. */
  private static void memberItems(
      Workspace.Unit unit, Type type, Map<String, Map<String, Object>> items) {
    if (type instanceof Type.ArrayType) {
      items.put("length", item("length", 5, "int length"));
    }
    if (!(type.erasure() instanceof Type.ClassType ct)) {
      return;
    }
    Set<ClassSymbol> hierarchy = new java.util.LinkedHashSet<>();
    collectHierarchy(ct.sym(), hierarchy);
    for (ClassSymbol c : hierarchy) {
      // Own members first, then supertypes, then Object's (sortText ranks, labels stay plain).
      String rank = c == ct.sym() ? "0" : c.binaryName().equals("java/lang/Object") ? "2" : "1";
      for (PropertySymbol p : c.properties()) {
        if (!p.isStatic()) {
          items.putIfAbsent(
              p.name(),
              ranked(item(p.name(), 10, show(p.type()) + " " + p.name() + accessors(p)), rank));
        }
      }
      for (FieldSymbol fs : c.fields()) {
        if (!fs.isStatic()
            && fs.property() == null
            && visible(fs.flags())
            && !fs.name().startsWith("$")) {
          items.putIfAbsent(
              fs.name(), ranked(item(fs.name(), 5, show(fs.type()) + " " + fs.name()), rank));
        }
      }
      for (MethodSymbol m : c.allMethods()) {
        if (m.isConstructor()
            || m.isStatic()
            || m.property() != null
            || !visible(m.flags())
            || m.has(Flags.SYNTHETIC)
            || m.name().contains("$")
            || m.name().startsWith("<")
            || m.has(Flags.PROTECTED) && rank.equals("2")) {
          continue; // Object's protected clone/finalize are not callable on other objects
        }
        items.putIfAbsent(m.name(), ranked(item(m.name(), 2, signature(m)), rank));
      }
    }
    for (String container : List.of("jsharp/collections/Sequences", "jsharp/text/Strings")) {
      ClassSymbol ext = unit.comp.lookupClass(container);
      if (ext == null) {
        continue;
      }
      for (MethodSymbol m : ext.allMethods()) {
        if (m.isExtension()
            && !m.params().isEmpty()
            && accepts(m.params().getFirst().type(), hierarchy, type)) {
          items.putIfAbsent(m.name(), ranked(item(m.name(), 2, signature(m)), "3"));
        }
      }
    }
  }

  private static Map<String, Object> ranked(Map<String, Object> item, String rank) {
    item.put("sortText", rank + item.get("label"));
    return item;
  }

  private static boolean accepts(Type param, Set<ClassSymbol> hierarchy, Type receiver) {
    Type erased = param.erasure();
    if (erased instanceof Type.ArrayType pa) {
      return receiver instanceof Type.ArrayType ra
          && (pa.elem() instanceof Type.PrimType) == (ra.elem() instanceof Type.PrimType)
          && (!(pa.elem() instanceof Type.PrimType) || pa.elem() == ra.elem());
    }
    return erased instanceof Type.ClassType pc && hierarchy.contains(pc.sym());
  }

  private static boolean visible(long flags) {
    return (flags & Flags.PRIVATE) == 0;
  }

  private static void collectHierarchy(ClassSymbol c, Set<ClassSymbol> out) {
    if (c == null || !out.add(c)) {
      return;
    }
    if (c.superclass() != null) {
      collectHierarchy(c.superclass().sym(), out);
    }
    for (Type.ClassType i : c.interfaces()) {
      collectHierarchy(i.sym(), out);
    }
  }

  // ------------------------------------------------------------------ plumbing

  private int offset(SourceFile f, Map<String, Object> pos) {
    return Workspace.offset(f.content(), num(pos, "line"), num(pos, "character"));
  }

  private static int num(Map<String, Object> m, String key) {
    return m.get(key) instanceof Number n ? n.intValue() : 0;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> m, String key) {
    return m.get(key) instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Map<String, Object> m, String key) {
    return m.get(key) instanceof List<?> x ? (List<Object>) x : List.of();
  }

  private static URI uri(Map<String, Object> p) {
    return URI.create((String) map(p, "textDocument").get("uri"));
  }

  private void respond(Object id, Object result) throws IOException {
    Map<String, Object> m = Json.obj("jsonrpc", "2.0", "id", id);
    m.put("result", result);
    transport.write(Json.write(m));
  }

  private void respondError(Object id, int code, String message) throws IOException {
    transport.write(
        Json.write(
            Json.obj(
                "jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message))));
  }

  private void notify(String method, Object params) throws IOException {
    transport.write(Json.write(Json.obj("jsonrpc", "2.0", "method", method, "params", params)));
  }
}
