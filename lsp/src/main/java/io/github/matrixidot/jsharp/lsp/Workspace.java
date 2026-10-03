package io.github.matrixidot.jsharp.lsp;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.classpath.ClassPath;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostics;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.syntax.Parser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The documents the editor has open, and analyses of the source units they belong to.
 *
 * <p>A document's <em>unit</em> is the set of files analyzed with it: every {@code .jsharp} file
 * under its source root (its directory, minus trailing directories that match its package) that has
 * no top-level statements of its own, plus the document itself. So scripts with top-level
 * statements stand alone while multi-file programs and libraries are analyzed together (D072). Open
 * documents override the files on disk.
 *
 * <p>Java sources of the same module join the unit for their declarations (D082): {@code .java}
 * files under the source root and, in the Gradle layout {@code src/<set>/jsharp}, under the sibling
 * {@code src/<set>/java}.
 */
final class Workspace {
  private static final Set<String> SKIPPED_DIRS =
      Set.of("build", "out", ".git", ".gradle", "node_modules");

  /** The open documents: URI to current text. */
  private final Map<URI, String> open = new LinkedHashMap<>();

  private final ClassPath classPath;
  private final Map<String, Unit> cache = new HashMap<>();

  /** A finished analysis of one unit. */
  static final class Unit {
    final Compilation comp;
    final Map<URI, SourceFile> files = new HashMap<>();
    final Map<SourceFile, URI> uris = new java.util.IdentityHashMap<>();

    Unit(Compilation comp) {
      this.comp = comp;
    }

    SourceFile file(URI uri) {
      return files.get(uri);
    }
  }

  Workspace(List<Path> classPath) {
    try {
      this.classPath =
          Compilation.openClassPath(CompilerOptions.defaults().withClassPath(classPath));
    } catch (RuntimeException e) {
      throw new IllegalStateException("cannot open the class path: " + e.getMessage(), e);
    }
  }

  void open(URI uri, String text) {
    open.put(uri, text);
    cache.clear();
  }

  void change(URI uri, String text) {
    open.put(uri, text);
    cache.clear();
  }

  void close(URI uri) {
    open.remove(uri);
    cache.clear();
  }

  Set<URI> openDocuments() {
    return open.keySet();
  }

  String text(URI uri) {
    String t = open.get(uri);
    if (t != null) {
      return t;
    }
    try {
      return Files.readString(Path.of(uri));
    } catch (IOException | RuntimeException e) {
      return "";
    }
  }

  /** The (cached) analysis of the unit containing {@code uri}. */
  Unit unit(URI uri) {
    List<URI> members = unitMembers(uri);
    String key = members.toString();
    Unit u = cache.get(key);
    if (u == null) {
      u = analyze(members, Map.of());
      cache.put(key, u);
    }
    return u;
  }

  /**
   * Analyzes the unit of {@code uri} with {@code uri}'s text replaced (used for completion, which
   * patches the text at the cursor); not cached.
   */
  Unit analyzePatched(URI uri, String patchedText) {
    return analyze(unitMembers(uri), Map.of(uri, patchedText));
  }

  private Unit analyze(List<URI> members, Map<URI, String> overrides) {
    List<SourceFile> files = new ArrayList<>();
    Map<SourceFile, URI> uris = new java.util.IdentityHashMap<>();
    for (URI u : members) {
      String text = overrides.containsKey(u) ? overrides.get(u) : text(u);
      SourceFile f = new SourceFile(displayPath(u), text);
      files.add(f);
      uris.put(f, u);
    }
    Compilation comp =
        new Compilation(files, CompilerOptions.defaults(), classPath).recordExpressionTypes();
    comp.analyze();
    Unit unit = new Unit(comp);
    for (var e : uris.entrySet()) {
      unit.uris.put(e.getKey(), e.getValue());
      unit.files.put(e.getValue(), e.getKey());
    }
    return unit;
  }

  private static String displayPath(URI uri) {
    try {
      return Path.of(uri).toString();
    } catch (RuntimeException e) {
      return uri.toString();
    }
  }

  /** The files analyzed together with {@code uri} (sorted, the document itself included). */
  List<URI> unitMembers(URI uri) {
    List<URI> out = new ArrayList<>();
    out.add(uri);
    Path file;
    try {
      file = Path.of(uri);
    } catch (RuntimeException e) {
      return out; // not a file (e.g. an untitled buffer)
    }
    Path root = sourceRoot(file, packageOf(text(uri)));
    if (root == null || !Files.isDirectory(root)) {
      return out;
    }
    try (Stream<Path> s = Files.walk(root, 12)) {
      for (Path p :
          s.filter(x -> x.toString().endsWith(LanguageInfo.FILE_EXTENSION))
              .filter(x -> !skipped(root, x))
              .sorted()
              .toList()) {
        URI u = p.toUri();
        if (!u.equals(uri) && !hasTopLevelStatements(text(u))) {
          out.add(u);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Path> javaRoots = new ArrayList<>(List.of(root));
    if (root.getFileName() != null
        && root.getFileName().toString().equals("jsharp")
        && root.getParent() != null) {
      javaRoots.add(root.getParent().resolve("java"));
    }
    for (Path javaRoot : javaRoots) {
      if (!Files.isDirectory(javaRoot)) {
        continue;
      }
      try (Stream<Path> s = Files.walk(javaRoot, 12)) {
        s.filter(x -> x.toString().endsWith(".java"))
            .filter(x -> !skipped(javaRoot, x))
            .sorted()
            .forEach(p -> out.add(p.toUri()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return out;
  }

  private static boolean skipped(Path root, Path p) {
    for (Path part : root.relativize(p)) {
      if (SKIPPED_DIRS.contains(part.toString())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The directory a file's package is relative to: its directory minus the trailing directories
   * that match the package's last segments ({@code src/report/x.jsharp} in package {@code
   * ledger.report} gives {@code src}); the file's own directory if none match.
   */
  static Path sourceRoot(Path file, String pkg) {
    Path dir = file.toAbsolutePath().getParent();
    if (dir == null || pkg.isEmpty()) {
      return dir;
    }
    String[] parts = pkg.split("\\.");
    Path d = dir;
    for (int i = parts.length - 1; i >= 0; i--) {
      if (d.getFileName() == null
          || !d.getFileName().toString().equals(parts[i])
          || d.getParent() == null) {
        break;
      }
      d = d.getParent();
    }
    return d;
  }

  private static CompilationUnit quickParse(String text) {
    return Parser.parse(new SourceFile("<scan>", text), new Diagnostics());
  }

  static String packageOf(String text) {
    CompilationUnit u = quickParse(text);
    return u.pkg() == null ? "" : u.pkg().name();
  }

  static boolean hasTopLevelStatements(String text) {
    for (Decl d : quickParse(text).members()) {
      if (d instanceof Decl.TopLevelStmt) {
        return true;
      }
    }
    return false;
  }

  /** Where the first top-level statement starts, or -1. */
  static int firstStatementOffset(String text) {
    for (Decl d : quickParse(text).members()) {
      if (d instanceof Decl.TopLevelStmt s) {
        return s.span().start();
      }
    }
    return -1;
  }

  // ------------------------------------------------------------------ positions

  /** Converts LSP (line, UTF-16 character) to a string offset. */
  static int offset(String text, int line, int character) {
    int off = 0;
    for (int l = 0; l < line && off < text.length(); l++) {
      int nl = text.indexOf('\n', off);
      if (nl < 0) {
        return text.length();
      }
      off = nl + 1;
    }
    int lineEnd = text.indexOf('\n', off);
    if (lineEnd < 0) {
      lineEnd = text.length();
    }
    return Math.min(off + character, lineEnd);
  }

  /** Converts a string offset to an LSP position. */
  static Map<String, Object> position(String text, int offset) {
    int line = 0;
    int lineStart = 0;
    int end = Math.min(offset, text.length());
    for (int i = 0; i < end; i++) {
      if (text.charAt(i) == '\n') {
        line++;
        lineStart = i + 1;
      }
    }
    return Json.obj("line", line, "character", end - lineStart);
  }

  static Map<String, Object> range(String text, int start, int end) {
    return Json.obj("start", position(text, start), "end", position(text, Math.max(start, end)));
  }
}
