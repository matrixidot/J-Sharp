package io.github.matrixidot.jsharp.lsp;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.Note;
import io.github.matrixidot.jsharp.compiler.ide.SourceIndex;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
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
 * practice). Supports full-document sync, diagnostics, hover, go-to-definition, references,
 * highlights, rename, signature help, document symbols, completion, and a "Run" code lens on
 * programs. Every edit re-analyzes the document's unit with the real compiler, so editor errors
 * match {@code jsharp check} exactly.
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
      } catch (RequestFailed e) {
        if (id != null) {
          respondError(id, -32803, e.getMessage()); // shown to the user as is
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
      case "textDocument/references" ->
          references(uri(p), map(p, "position"), bool(map(p, "context"), "includeDeclaration"));
      case "textDocument/documentHighlight" -> highlights(uri(p), map(p, "position"));
      case "textDocument/prepareRename" -> prepareRename(uri(p), map(p, "position"));
      case "textDocument/rename" -> rename(uri(p), map(p, "position"), (String) p.get("newName"));
      case "textDocument/signatureHelp" -> signatureHelp(uri(p), map(p, "position"));
      case "textDocument/codeLens" -> codeLenses(uri(p));
      case "textDocument/semanticTokens/full" -> semanticTokens(uri(p));
      case "jsharp/programFiles" -> programFiles(uri(p));
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
            "textDocumentSync",
            Json.obj("openClose", true, "change", 1, "save", true),
            "hoverProvider",
            true,
            "definitionProvider",
            true,
            "documentSymbolProvider",
            true,
            "completionProvider",
            Json.obj("triggerCharacters", List.of(".")),
            "referencesProvider",
            true,
            "documentHighlightProvider",
            true,
            "renameProvider",
            Json.obj("prepareProvider", true),
            "signatureHelpProvider",
            Json.obj("triggerCharacters", List.of("(", ","), "retriggerCharacters", List.of(",")),
            "codeLensProvider",
            Json.obj("resolveProvider", false),
            "semanticTokensProvider",
            Json.obj(
                "legend",
                Json.obj("tokenTypes", TOKEN_TYPES, "tokenModifiers", TOKEN_MODIFIERS),
                "full",
                true));
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

  /** A request the server understood but cannot carry out; the message is for the user. */
  static final class RequestFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    RequestFailed(String message) {
      super(message, null, false, false);
    }
  }

  /** A J#-style one-line description of what a reference denotes. */
  static String describe(SourceIndex.Ref r) {
    return switch (r.symbol()) {
      case null -> r.type() == null ? null : r.type().display();
      case VarSymbol v when v.kind() == VarSymbol.Kind.PARAM ->
          "(parameter) " + v.name() + ": " + show(v.type());
      case io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol tv ->
          "(type parameter) " + tv.name();
      case VarSymbol v ->
          (v.isAtomic() ? "atomic " : "")
              + (v.has(Flags.FINAL) ? "val " : "var ")
              + v.name()
              + ": "
              + show(v.type());
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
    } else if (m.has(Flags.LOCAL)) {
      sb.append(m.name()); // a local function
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
      sb.append(show(p.type()));
      if (!isPlaceholderName(p.name())) {
        sb.append(' ').append(p.name()); // JDK class files keep no names: arg0, arg1, ...
      }
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

  private static boolean isPlaceholderName(String name) {
    return name == null || name.matches("arg\\d+");
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

  // ------------------------------------------------------------------ references & rename

  /** The symbol whose name is at {@code offset}, preferring an exact identifier match. */
  private static Optional<SourceIndex.Ref> symbolAt(SourceIndex index, SourceFile f, int offset) {
    for (SourceIndex.Ref r : index.refs(f)) {
      Span n = r.nameSpan();
      if (r.symbol() != null && n != null && n.start() <= offset && offset <= n.end()) {
        return Optional.of(r);
      }
    }
    return index.at(f, offset).filter(r -> r.symbol() != null);
  }

  private Object references(URI uri, Map<String, Object> pos, boolean includeDeclaration) {
    Workspace.Unit unit = workspace().unit(uri);
    SourceFile f = unit.file(uri);
    SourceIndex index = unit.comp.index();
    Optional<SourceIndex.Ref> ref = symbolAt(index, f, offset(f, pos));
    if (ref.isEmpty()) {
      return List.of();
    }
    Optional<SourceIndex.Location> decl =
        index.declaration(SourceIndex.canonical(ref.get().symbol()));
    List<Object> out = new ArrayList<>();
    for (SourceIndex.Location loc : index.references(ref.get().symbol())) {
      boolean isDecl =
          decl.isPresent()
              && decl.get().file() == loc.file()
              && decl.get().span().start() == loc.span().start();
      URI target = unit.uris.get(loc.file());
      if (target != null && (includeDeclaration || !isDecl)) {
        out.add(location(target, loc));
      }
    }
    return out;
  }

  private Object highlights(URI uri, Map<String, Object> pos) {
    Workspace.Unit unit = workspace().unit(uri);
    SourceFile f = unit.file(uri);
    SourceIndex index = unit.comp.index();
    Optional<SourceIndex.Ref> ref = symbolAt(index, f, offset(f, pos));
    if (ref.isEmpty()) {
      return List.of();
    }
    List<Object> out = new ArrayList<>();
    for (SourceIndex.Location loc : index.references(ref.get().symbol())) {
      if (loc.file() == f) {
        out.add(
            Json.obj("range", Workspace.range(f.content(), loc.span().start(), loc.span().end())));
      }
    }
    return out;
  }

  /** The renameable symbol at a position, or a {@link RequestFailed} explaining why not. */
  private SourceIndex.Ref renameTarget(Workspace.Unit unit, SourceFile f, int offset) {
    SourceIndex index = unit.comp.index();
    SourceIndex.Ref ref =
        symbolAt(index, f, offset).orElseThrow(() -> new RequestFailed("Nothing to rename here."));
    Symbol s = SourceIndex.canonical(ref.symbol());
    if (s instanceof ClassSymbol || s instanceof MethodSymbol m && m.isConstructor()) {
      throw new RequestFailed("Renaming types is not supported yet.");
    }
    if (s instanceof MethodSymbol m && m.has(Flags.OPERATOR)) {
      throw new RequestFailed("Operators cannot be renamed.");
    }
    if (index.declaration(s).isEmpty()
        || ref.nameSpan() == null
        || unit.uris.get(index.declaration(s).get().file()) == null) {
      throw new RequestFailed("'" + s.name() + "' is declared outside this project.");
    }
    return ref;
  }

  private Object prepareRename(URI uri, Map<String, Object> pos) {
    Workspace.Unit unit = workspace().unit(uri);
    SourceFile f = unit.file(uri);
    SourceIndex.Ref ref = renameTarget(unit, f, offset(f, pos));
    Span n = ref.nameSpan();
    return Json.obj(
        "range", Workspace.range(f.content(), n.start(), n.end()),
        "placeholder", f.content().substring(n.start(), n.end()));
  }

  private Object rename(URI uri, Map<String, Object> pos, String newName) {
    Workspace.Unit unit = workspace().unit(uri);
    SourceFile f = unit.file(uri);
    if (newName == null
        || !newName.matches("[A-Za-z_][A-Za-z0-9_]*")
        || io.github.matrixidot.jsharp.compiler.syntax.TokenKind.keyword(newName) != null) {
      throw new RequestFailed("'" + newName + "' is not a valid name.");
    }
    SourceIndex.Ref ref = renameTarget(unit, f, offset(f, pos));
    Map<String, Object> changes = new LinkedHashMap<>();
    for (SourceIndex.Location loc : unit.comp.index().references(ref.symbol())) {
      URI target = unit.uris.get(loc.file());
      if (target == null) {
        continue;
      }
      @SuppressWarnings("unchecked")
      List<Object> edits =
          (List<Object>) changes.computeIfAbsent(target.toString(), k -> new ArrayList<>());
      edits.add(
          Json.obj(
              "range",
              Workspace.range(loc.file().content(), loc.span().start(), loc.span().end()),
              "newText",
              newName));
    }
    return Json.obj("changes", changes);
  }

  private static Map<String, Object> location(URI uri, SourceIndex.Location loc) {
    return Json.obj(
        "uri",
        uri.toString(),
        "range",
        Workspace.range(loc.file().content(), loc.span().start(), loc.span().end()));
  }

  // ------------------------------------------------------------------ signature help

  /** An open call around the cursor: the '(' and how many arguments precede the cursor. */
  private record OpenCall(int paren, int commas) {}

  /**
   * The innermost unclosed '(' before {@code offset}, skipping strings, characters and comments;
   * commas count only directly inside it (not in nested brackets, braces or calls).
   */
  static OpenCall openCall(String text, int offset) {
    java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>(); // {position, commas, char}
    int i = 0;
    while (i < offset && i < text.length()) {
      char c = text.charAt(i);
      if (c == '"' || c == '\'') {
        i++;
        while (i < offset && i < text.length() && text.charAt(i) != c && text.charAt(i) != '\n') {
          i += text.charAt(i) == '\\' ? 2 : 1;
        }
      } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
        while (i < text.length() && text.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
        int end = text.indexOf("*/", i + 2);
        i = end < 0 ? text.length() : end + 1;
      } else if (c == '(' || c == '[' || c == '{') {
        stack.push(new int[] {i, 0, c});
      } else if (c == ')' || c == ']' || c == '}') {
        if (!stack.isEmpty()) {
          stack.pop();
        }
      } else if (c == ',' && !stack.isEmpty() && stack.peek()[2] == '(') {
        stack.peek()[1]++;
      }
      i++;
    }
    for (int[] top : stack) {
      if (top[2] == '(') {
        return new OpenCall(top[0], top[1]);
      }
      return null; // inside a block or brackets, not directly in a call
    }
    return null;
  }

  private Object signatureHelp(URI uri, Map<String, Object> pos) {
    String text = workspace().text(uri);
    int offset = Workspace.offset(text, num(pos, "line"), num(pos, "character"));
    OpenCall call = openCall(text, offset);
    if (call == null) {
      return null;
    }
    int nameEnd = call.paren();
    while (nameEnd > 0 && Character.isWhitespace(text.charAt(nameEnd - 1))) {
      nameEnd--;
    }
    int nameStart = nameEnd;
    while (nameStart > 0 && Character.isJavaIdentifierPart(text.charAt(nameStart - 1))) {
      nameStart--;
    }
    if (nameStart == nameEnd) {
      return null;
    }
    String name = text.substring(nameStart, nameEnd);
    List<MethodSymbol> cands = callCandidates(uri, text, name, nameStart, call.paren());
    if (cands.isEmpty()) {
      return null;
    }
    List<Object> sigs = new ArrayList<>();
    int active = 0;
    for (int i = 0; i < cands.size(); i++) {
      MethodSymbol m = cands.get(i);
      sigs.add(signatureInfo(m));
      if (active == 0 && visibleParams(m).size() > call.commas()) {
        active = i;
      }
    }
    return Json.obj(
        "signatures", sigs, "activeSignature", active, "activeParameter", call.commas());
  }

  private static List<MethodSymbol.Param> visibleParams(MethodSymbol m) {
    List<MethodSymbol.Param> ps = m.params();
    return m.isExtension() && !ps.isEmpty() ? ps.subList(1, ps.size()) : ps;
  }

  private static Map<String, Object> signatureInfo(MethodSymbol m) {
    StringBuilder label = new StringBuilder();
    label.append(m.isConstructor() ? "new " + m.owner().displayName() : m.name()).append('(');
    List<Object> params = new ArrayList<>();
    List<MethodSymbol.Param> ps = visibleParams(m);
    for (int i = 0; i < ps.size(); i++) {
      if (i > 0) {
        label.append(", ");
      }
      int start = label.length();
      MethodSymbol.Param p = ps.get(i);
      label.append(show(p.type()));
      if (!isPlaceholderName(p.name())) {
        label.append(' ').append(p.name());
      }
      if (p.hasDefault()) {
        label
            .append(" = ")
            .append(p.defaultValue() instanceof String s ? "\"" + s + "\"" : p.defaultValue());
      }
      params.add(Json.obj("label", List.of(start, label.length())));
    }
    label.append(')');
    if (!m.isConstructor()) {
      label.append(": ").append(show(m.returnType()));
    }
    return Json.obj("label", label.toString(), "parameters", params);
  }

  /** The overloads a call written as {@code ...name(} may mean. */
  private List<MethodSymbol> callCandidates(
      URI uri, String text, String name, int nameStart, int paren) {
    int before = nameStart;
    while (before > 0 && Character.isWhitespace(text.charAt(before - 1))) {
      before--;
    }
    // new Name(
    if (before >= 3 && text.startsWith("new", before - 3)) {
      for (String end : List.of("", ";")) {
        String patched =
            text.substring(0, before - 3) + "typeof(" + name + ")" + end + skipCall(text, paren);
        Workspace.Unit unit = workspace().analyzePatched(uri, patched);
        SourceFile f = unit.file(uri);
        Optional<SourceIndex.Ref> lit =
            unit.comp.index().endingAt(f, before - 3 + 8 + name.length());
        if (lit.isPresent()
            && lit.get().type() instanceof Type.ClassType cls
            && !cls.args().isEmpty()
            && cls.args().getFirst() instanceof Type.ClassType target) {
          return target.sym().methods(MethodSymbol.CONSTRUCTOR).stream()
              .filter(m -> visible(m.flags()))
              .toList();
        }
      }
      return List.of();
    }
    // receiver.name(
    if (before > 0 && text.charAt(before - 1) == '.') {
      int dot = before - 1;
      for (String patch : List.of(COMPLETION_MARKER, COMPLETION_MARKER + ";")) {
        String patched = text.substring(0, dot + 1) + patch + skipCall(text, paren);
        Workspace.Unit unit = workspace().analyzePatched(uri, patched);
        SourceFile f = unit.file(uri);
        Optional<SourceIndex.Ref> recv = unit.comp.index().endingAt(f, dot);
        if (recv.isPresent() && recv.get().type() != null) {
          return methodsNamed(unit, recv.get().type(), name);
        }
      }
      return List.of();
    }
    // name(: local functions, enclosing classes, top-level functions, the Prelude. The open call
    // is cut out so the rest of the file parses.
    Workspace.Unit unit =
        workspace().analyzePatched(uri, text.substring(0, nameStart) + skipCall(text, paren));
    SourceFile f = unit.file(uri);
    List<MethodSymbol> out = new ArrayList<>();
    for (SourceIndex.Ref r : unit.comp.index().refs(f)) {
      if (r.declaration()
          && r.symbol() instanceof MethodSymbol m
          && m.name().equals(name)
          && (m.has(Flags.LOCAL) ? r.span().start() < nameStart : true)
          && !out.contains(m)) {
        out.add(m);
      }
    }
    for (SourceFile other : unit.uris.keySet()) {
      for (SourceIndex.Ref r : unit.comp.index().refs(other)) {
        if (r.declaration()
            && r.symbol() instanceof MethodSymbol m
            && m.name().equals(name)
            && m.owner().has(Flags.MODULE)
            && !out.contains(m)) {
          out.add(m);
        }
      }
    }
    ClassSymbol prelude = unit.comp.lookupClass("jsharp/core/Prelude");
    if (out.isEmpty() && prelude != null) {
      out.addAll(prelude.methods(name).stream().filter(m -> m.has(Flags.PUBLIC)).toList());
    }
    return out;
  }

  /** The text after the call starting at {@code paren}: from its ')' (or the line's end). */
  private static String skipCall(String text, int paren) {
    int depth = 0;
    for (int i = paren; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return text.substring(i + 1);
      } else if (c == '\n') {
        return text.substring(i);
      }
    }
    return "";
  }

  /** Methods named {@code name} on {@code type}, including applicable stdlib extensions. */
  private static List<MethodSymbol> methodsNamed(Workspace.Unit unit, Type type, String name) {
    List<MethodSymbol> out = new ArrayList<>();
    if (!(type.erasure() instanceof Type.ClassType ct)) {
      return out;
    }
    Set<ClassSymbol> hierarchy = new java.util.LinkedHashSet<>();
    collectHierarchy(ct.sym(), hierarchy);
    Set<String> seen = new java.util.HashSet<>();
    for (ClassSymbol c : hierarchy) {
      for (MethodSymbol m : c.methods(name)) {
        if (visible(m.flags()) && !m.has(Flags.SYNTHETIC) && seen.add(signature(m))) {
          out.add(m);
        }
      }
    }
    for (String container :
        List.of(
            "jsharp/collections/Sequences",
            "jsharp/collections/LongSums",
            "jsharp/collections/DoubleSums",
            "jsharp/text/Strings")) {
      ClassSymbol ext = unit.comp.lookupClass(container);
      if (ext == null) {
        continue;
      }
      for (MethodSymbol m : ext.methods(name)) {
        if (m.isExtension()
            && !m.params().isEmpty()
            && accepts(m.params().getFirst().type(), hierarchy, type)) {
          out.add(m);
        }
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ semantic tokens

  /** Standard LSP token types, by index (editors map them onto their color schemes). */
  static final List<String> TOKEN_TYPES =
      List.of(
          "class",
          "interface",
          "enum",
          "typeParameter",
          "method",
          "function",
          "property",
          "variable",
          "parameter",
          "enumMember");

  static final List<String> TOKEN_MODIFIERS = List.of("declaration", "static", "readonly");

  /**
   * What each name in the file is (class, method, property, parameter, ...), so editors can color
   * them like Java. Encoded as LSP relative 5-tuples: line delta, start delta, length, type,
   * modifier bits.
   */
  private Object semanticTokens(URI uri) {
    Workspace.Unit unit = workspace().unit(uri);
    SourceFile f = unit.file(uri);
    String text = f.content();
    java.util.TreeMap<Integer, int[]> byStart =
        new java.util.TreeMap<>(); // start -> {len, type, mods}
    for (SourceIndex.Ref r : unit.comp.index().refs(f)) {
      Span n = r.nameSpan();
      if (r.symbol() == null || n == null || n.end() <= n.start()) {
        continue;
      }
      int type = tokenType(r.symbol());
      if (type < 0
          || byStart.containsKey(n.start())
          || r.symbol() instanceof MethodSymbol op && op.has(Flags.OPERATOR)) {
        continue; // operators keep their operator color
      }
      int mods = r.declaration() ? 1 : 0;
      if (r.symbol().isStatic()
          && !(r.symbol() instanceof ClassSymbol)
          && !(r.symbol() instanceof MethodSymbol m && m.owner().has(Flags.MODULE))) {
        mods |= 2;
      }
      if (r.symbol() instanceof VarSymbol v && v.isFinal()
          || r.symbol() instanceof FieldSymbol fs && fs.has(Flags.FINAL)) {
        mods |= 4;
      }
      byStart.put(n.start(), new int[] {n.end() - n.start(), type, mods});
    }
    List<Object> data = new ArrayList<>();
    int line = 0;
    int lineStart = 0;
    int prevLine = 0;
    int prevChar = 0;
    int scanned = 0;
    for (var e : byStart.entrySet()) {
      int start = e.getKey();
      for (; scanned < start && scanned < text.length(); scanned++) {
        if (text.charAt(scanned) == '\n') {
          line++;
          lineStart = scanned + 1;
        }
      }
      int ch = start - lineStart;
      data.add(line - prevLine);
      data.add(line == prevLine ? ch - prevChar : ch);
      data.add(e.getValue()[0]);
      data.add(e.getValue()[1]);
      data.add(e.getValue()[2]);
      prevLine = line;
      prevChar = ch;
    }
    return Json.obj("data", data);
  }

  private static int tokenType(Symbol s) {
    String t =
        switch (s) {
          case ClassSymbol c -> c.isInterface() ? "interface" : c.isEnum() ? "enum" : "class";
          case io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol tv -> "typeParameter";
          case MethodSymbol m when m.isConstructor() -> "class";
          case MethodSymbol m when m.owner().has(Flags.MODULE) || m.has(Flags.LOCAL) -> "function";
          case MethodSymbol m -> "method";
          case PropertySymbol p -> "property";
          case FieldSymbol f when f.has(Flags.ENUM_CONSTANT) -> "enumMember";
          case FieldSymbol f -> "property";
          case VarSymbol v when v.kind() == VarSymbol.Kind.PARAM -> "parameter";
          case VarSymbol v -> "variable";
          default -> null;
        };
    return t == null ? -1 : TOKEN_TYPES.indexOf(t);
  }

  // ------------------------------------------------------------------ run

  /** "Run" above a program's entry point: top-level statements or a main method. */
  private Object codeLenses(URI uri) {
    String text = workspace().text(uri);
    int at = -1;
    if (Workspace.hasTopLevelStatements(text)) {
      at = Workspace.firstStatementOffset(text);
    } else {
      Workspace.Unit unit = workspace().unit(uri);
      SourceFile f = unit.file(uri);
      for (SourceIndex.Ref r : unit.comp.index().refs(f)) {
        if (r.declaration()
            && r.symbol() instanceof MethodSymbol m
            && m.name().equals("main")
            && m.isStatic()) {
          at = r.span().start();
          break;
        }
      }
    }
    if (at < 0) {
      return List.of();
    }
    Map<String, Object> range = Workspace.range(text, at, at);
    // Arguments: the document, then the files to run it with (see programFiles).
    return List.of(
        Json.obj(
            "range",
            range,
            "command",
            Json.obj(
                "title",
                "\u25B6 Run",
                "command",
                "jsharp.run",
                "arguments",
                List.of(uri.toString(), programFiles(uri)))));
  }

  /** The files {@code jsharp run} needs for the program in {@code uri}: its unit. */
  private Object programFiles(URI uri) {
    List<Object> out = new ArrayList<>();
    for (URI u : workspace().unitMembers(uri)) {
      try {
        out.add(Path.of(u).toString());
      } catch (RuntimeException e) {
        // not a file (an untitled buffer): cannot be run from disk
      }
    }
    return out;
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

  private static boolean bool(Map<String, Object> m, String key) {
    return Boolean.TRUE.equals(m.get(key));
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
