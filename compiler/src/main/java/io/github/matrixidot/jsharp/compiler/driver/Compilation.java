package io.github.matrixidot.jsharp.compiler.driver;

import io.github.matrixidot.jsharp.compiler.Context;
import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.bound.BClass;
import io.github.matrixidot.jsharp.compiler.check.Attr;
import io.github.matrixidot.jsharp.compiler.check.ClassChecker;
import io.github.matrixidot.jsharp.compiler.classpath.ClassPath;
import io.github.matrixidot.jsharp.compiler.classpath.JavaSourceLoader;
import io.github.matrixidot.jsharp.compiler.codegen.ClassGen;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostics;
import io.github.matrixidot.jsharp.compiler.ide.SourceIndex;
import io.github.matrixidot.jsharp.compiler.lower.Lowerer;
import io.github.matrixidot.jsharp.compiler.resolve.Enter;
import io.github.matrixidot.jsharp.compiler.resolve.MemberEnter;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import io.github.matrixidot.jsharp.compiler.syntax.Parser;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * One run of the compiler over a set of source files. Phases: parse, enter/resolve, (check, lower,
 * generate). Each phase only runs if the previous ones reported no errors, so users see root causes
 * rather than cascades.
 */
public final class Compilation {
  private final CompilerOptions options;
  private final List<SourceFile> sources;
  private final Diagnostics diags;
  private final ClassPath classPath;
  private final boolean ownsClassPath;
  private final List<CompilationUnit> units = new ArrayList<>();
  private Context ctx;
  private Enter enter;
  private MemberEnter memberEnter;
  private SourceFile currentFile;
  private Attr attr;
  private List<BClass> checked = List.of();
  private JavaSourceLoader javaLoader;
  private List<ClassSymbol> javaClasses = List.of();
  private boolean compileJava = true;

  /**
   * @param sharedClassPath a class path to reuse (must already include the runtime), or null to
   *     build one from the options plus the runtime library
   */
  public Compilation(List<SourceFile> sources, CompilerOptions options, ClassPath sharedClassPath) {
    this.sources = List.copyOf(sources);
    this.options = options;
    this.diags = new Diagnostics(options.warningsAsErrors());
    if (sharedClassPath != null) {
      this.classPath = sharedClassPath;
      this.ownsClassPath = false;
    } else {
      this.classPath = openClassPath(options);
      this.ownsClassPath = true;
    }
  }

  /** Builds the class path: runtime library first, then the user's entries. */
  public static ClassPath openClassPath(CompilerOptions options) {
    String problem = CompilerOptions.releaseProblem(options.release());
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    List<Path> cp = new ArrayList<>();
    cp.addAll(RuntimeLocator.findAll());
    cp.addAll(options.classPath());
    try {
      return ClassPath.of(cp, options.release());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Java sources ({@code .java} files among the sources) are only read for their declarations;
   * another tool compiles them (the Gradle plugin leaves them to {@code compileJava}). By default
   * {@link #compile()} also compiles them with javac, after the J# classes (D082).
   */
  public Compilation javaDeclarationsOnly() {
    compileJava = false;
    return this;
  }

  public Diagnostics diagnostics() {
    return diags;
  }

  public Context context() {
    return ctx;
  }

  public Enter enter() {
    return enter;
  }

  /** Runs parsing and semantic analysis. Returns true if there were no errors. */
  public boolean analyze() {
    return guarded(
        () -> {
          long t = System.nanoTime();
          for (SourceFile f : sources) {
            if (!JavaSourceLoader.isJava(f)) {
              currentFile = f;
              units.add(Parser.parse(f, diags));
            }
          }
          currentFile = null;
          t = phase("parse", t);
          if (diags.hasErrors()) {
            return;
          }
          ctx = new Context(new Symtab(classPath), diags, options);
          if (recordTypes) {
            ctx.typeRefs = new IdentityHashMap<>();
          }
          javaLoader = new JavaSourceLoader(ctx.syms, diags, options.release());
          javaClasses = javaLoader.load(javaSources());
          if (diags.hasErrors()) {
            return;
          }
          memberEnter = new MemberEnter(ctx);
          enter = new Enter(ctx, memberEnter);
          enter.enterAll(units);
          t = phase("enter", t);
          if (diags.hasErrors()) {
            return;
          }
          attr = new Attr(ctx, memberEnter);
          if (recordTypes) {
            attr.recordTypesInto(recordedTypes);
          }
          checked = ClassChecker.checkAll(attr, enter.enteredClasses());
          if (!diags.hasErrors()) {
            List<ClassSymbol> all = new ArrayList<>(enter.enteredClasses());
            all.addAll(javaClasses);
            checkSingleEntryPoint(all);
          }
          phase("check", t);
        });
  }

  private final Map<String, byte[]> classFiles = new LinkedHashMap<>();
  private String mainClass;

  /**
   * Runs all phases and generates class files (kept in memory and, if an output directory is set,
   * written there). Returns true on success.
   */
  public boolean compile() {
    if (!analyze()) {
      return false;
    }
    return guarded(
        () -> {
          long t = System.nanoTime();
          var types = attr.types();
          var lowerer = new Lowerer(ctx, types, attr.anonymousSuperConstructors());
          List<BClass> lowered = lowerer.lowerAll(checked);
          t = phase("lower", t);
          Map<ClassSymbol, List<ClassSymbol>> nests = new IdentityHashMap<>();
          for (BClass c : lowered) {
            if (c.sym().outer() != null) {
              nests.computeIfAbsent(c.sym().outermost(), k -> new ArrayList<>()).add(c.sym());
            }
          }
          var gen = new ClassGen(types, options.emitDebugInfo(), options.release(), nests);
          for (BClass c : lowered) {
            currentFile =
                c.sym().outermost().unit() != null ? c.sym().outermost().unit().file() : null;
            classFiles.put(c.sym().binaryName(), gen.generate(c));
            for (var m : c.methods()) {
              if (m.sym().has(Flags.ENTRY_POINT) || mainClass == null && isMain(m.sym())) {
                mainClass = c.sym().binaryName().replace('/', '.');
              }
            }
          }
          currentFile = null;
          t = phase("codegen", t);
          if (compileJava && !javaSources().isEmpty()) {
            if (!compileJavaSources()) {
              return;
            }
            t = phase("javac", t);
          }
          if (options.outputDir() != null) {
            writeClassFiles(options.outputDir());
            phase("write", t);
          }
        });
  }

  /**
   * A program has at most one entry point: top-level statements or one {@code static void
   * main(String[])}. More than one is an error rather than an arbitrary choice, reported at each of
   * them so it shows in whichever file an editor analyzes.
   */
  private void checkSingleEntryPoint(List<ClassSymbol> classes) {
    List<MethodSymbol> mains = new ArrayList<>();
    ArrayDeque<ClassSymbol> todo = new ArrayDeque<>(classes);
    while (!todo.isEmpty()) {
      var c = todo.pop();
      todo.addAll(c.memberTypes());
      for (var m : c.methods("main")) {
        if (m.has(Flags.ENTRY_POINT) || isMain(m)) {
          mains.add(m);
        }
      }
    }
    if (mains.size() < 2) {
      return;
    }
    for (var m : mains) {
      var d =
          Diagnostic.error(
              Code.MULTIPLE_ENTRY_POINTS,
              fileOf(m),
              entrySpan(m),
              "this program has more than one entry point");
      for (var other : mains) {
        if (other != m) {
          d.note("another entry point: " + describeEntry(other), fileOf(other), entrySpan(other));
        }
      }
      d.help("keep one main method (or top-level statements), or compile the programs separately");
      diags.report(d.build());
    }
  }

  private static SourceFile fileOf(MethodSymbol m) {
    var top = m.owner().outermost();
    return top.unit() != null ? top.unit().file() : top.javaFile();
  }

  private Span entrySpan(MethodSymbol m) {
    if (m.decl() instanceof Decl.Method dm) {
      return dm.nameSpan();
    }
    if (javaLoader != null && javaLoader.spanOf(m) != null) {
      return javaLoader.spanOf(m);
    }
    var unit = m.owner().outermost().unit();
    if (unit != null) {
      for (var d : unit.members()) {
        if (d instanceof Decl.TopLevelStmt s) {
          return s.span();
        }
      }
    }
    return new Span(0, 0);
  }

  private static String describeEntry(MethodSymbol m) {
    return m.has(Flags.ENTRY_POINT)
        ? "top-level statements"
        : m.owner().has(Flags.MODULE)
            ? "top-level function main"
            : m.owner().displayName() + ".main";
  }

  private static boolean isMain(MethodSymbol m) {
    return m.name().equals("main")
        && m.isStatic()
        && m.returnType() == Type.PrimType.VOID
        && m.params().size() == 1
        && m.params().getFirst().type() instanceof Type.ArrayType at
        && at.elem().erasure() instanceof Type.ClassType ct
        && ct.sym().binaryName().equals("java/lang/String");
  }

  private List<SourceFile> javaSources() {
    return sources.stream().filter(JavaSourceLoader::isJava).toList();
  }

  /**
   * Compiles the Java sources with javac against the J# classes just generated, and adds the
   * resulting class files. javac's errors and warnings become JS1000/JS1001 diagnostics.
   */
  private boolean compileJavaSources() {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    Path tmp = null;
    try {
      tmp = Files.createTempDirectory("jsharp-javac");
      Path jsharpOut = tmp.resolve("jsharp");
      Path javaOut = tmp.resolve("java");
      Files.createDirectories(javaOut);
      writeClassFiles(jsharpOut);
      List<String> cp = new ArrayList<>();
      cp.add(jsharpOut.toString());
      RuntimeLocator.findAll().forEach(p -> cp.add(p.toString()));
      options.classPath().forEach(p -> cp.add(p.toString()));
      List<String> args =
          new ArrayList<>(
              List.of(
                  "-proc:none",
                  "-implicit:none",
                  "-parameters",
                  "--release",
                  String.valueOf(options.release()),
                  "-d",
                  javaOut.toString(),
                  "-cp",
                  String.join(File.pathSeparator, cp)));
      if (options.emitDebugInfo()) {
        args.add("-g");
      }
      var collected = new DiagnosticCollector<JavaFileObject>();
      List<JavaFileObject> files =
          javaSources().stream().map(JavaSourceLoader::fileObject).toList();
      boolean ok = javac.getTask(null, null, collected, args, null, files).call();
      JavaSourceLoader.reportJavac(collected, diags, javaSources());
      if (!ok || diags.hasErrors()) {
        return false;
      }
      List<Path> outputs;
      try (var s = Files.walk(javaOut)) {
        outputs = s.filter(p -> p.toString().endsWith(".class")).sorted().toList();
      }
      for (Path p : outputs) {
        String rel = javaOut.relativize(p).toString().replace('\\', '/');
        classFiles.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
      }
      if (mainClass == null) {
        mainClass = javaMainClass();
      }
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      if (tmp != null) {
        deleteTree(tmp);
      }
    }
  }

  /** The Java class declaring {@code static void main(String[])}, if there is exactly one. */
  private String javaMainClass() {
    ArrayDeque<ClassSymbol> todo = new ArrayDeque<>(javaClasses);
    while (!todo.isEmpty()) {
      var c = todo.pop();
      todo.addAll(c.memberTypes());
      for (var m : c.methods("main")) {
        if (isMain(m)) {
          return c.binaryName().replace('/', '.');
        }
      }
    }
    return null;
  }

  private static void deleteTree(Path dir) {
    try (var s = Files.walk(dir)) {
      for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException e) {
      // Best effort: the directory is in the system temporary directory.
    }
  }

  private void writeClassFiles(Path dir) {
    try {
      for (var e : classFiles.entrySet()) {
        Path out = dir.resolve(e.getKey() + ".class");
        Files.createDirectories(out.getParent());
        Files.write(out, e.getValue());
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private SourceIndex index;
  private boolean recordTypes;
  private final Map<SourceFile, Map<Span, Type>> recordedTypes = new IdentityHashMap<>();

  /**
   * Makes the next {@link #analyze()} record the type of every attributed expression for {@link
   * #index()}, including subexpressions of erroneous code (for editor completion).
   */
  public Compilation recordExpressionTypes() {
    recordTypes = true;
    return this;
  }

  /**
   * Position index of the analyzed sources for editor tooling (hover, definition, completion);
   * empty if analysis stopped before type checking.
   */
  public SourceIndex index() {
    if (index == null) {
      index =
          SourceIndex.build(
              checked == null ? List.of() : checked,
              recordedTypes,
              ctx == null || ctx.typeRefs == null ? Map.of() : ctx.typeRefs);
    }
    return index;
  }

  /** A class by JVM internal name ({@code java/util/List}), or null (needs a finished analysis). */
  public ClassSymbol lookupClass(String binaryName) {
    return ctx == null ? null : ctx.syms.lookup(binaryName);
  }

  /** Generated class files by JVM internal name. */
  public Map<String, byte[]> classFiles() {
    return classFiles;
  }

  /** The class containing the entry point (top-level statements or main), or null. */
  public String mainClass() {
    return mainClass;
  }

  /**
   * Runs {@code body}, converting unexpected compiler exceptions into an internal-error diagnostic
   * (never a stack trace for the user).
   */
  private static final boolean TIMINGS = Boolean.getBoolean("jsharp.timings");

  /** With {@code -Djsharp.timings=true}, prints the duration of a phase to stderr. */
  private static long phase(String name, long start) {
    long now = System.nanoTime();
    if (TIMINGS) {
      System.err.printf("[jsharp] %-8s %6.1f ms%n", name, (now - start) / 1e6);
    }
    return now;
  }

  /** Stack size for compiler work: phases recurse over the tree (reserved, not committed). */
  private static final long COMPILER_STACK = 512L << 20;

  private boolean guarded(Runnable body) {
    try {
      runWithLargeStack(body);
    } catch (RuntimeException | StackOverflowError | AssertionError e) {
      String where = currentFile != null ? " while compiling " + currentFile.path() : "";
      diags.report(
          Diagnostic.error(
                  Code.INTERNAL_ERROR,
                  currentFile,
                  currentFile != null ? new Span(0, 0) : null,
                  "internal compiler error" + where + ": " + e)
              .note("compiler version " + LanguageInfo.ID + " " + LanguageInfo.VERSION)
              .help(
                  "this is a bug in the J# compiler; please report it with the file that triggered it")
              .build());
      if (Boolean.getBoolean("jsharp.debug")) {
        e.printStackTrace();
      }
    } finally {
      if (ownsClassPath) {
        // The class path stays open for later phases; closed in close().
      }
    }
    return !diags.hasErrors();
  }

  /**
   * Runs {@code body} on a thread with a large stack, so deeply nested (but accepted) code cannot
   * overflow the recursive phases; exceptions are rethrown on the calling thread.
   */
  private static void runWithLargeStack(Runnable body) {
    Throwable[] failure = new Throwable[1];
    Thread t =
        new Thread(
            null,
            () -> {
              try {
                body.run();
              } catch (Throwable e) {
                failure[0] = e;
              }
            },
            "jsharp-compiler",
            COMPILER_STACK);
    t.start();
    try {
      t.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while compiling", e);
    }
    switch (failure[0]) {
      case null -> {}
      case RuntimeException re -> throw re;
      case Error err -> throw err;
      default -> throw new IllegalStateException(failure[0]);
    }
  }

  /** Releases the class path if this compilation created it. */
  public void close() {
    if (ownsClassPath) {
      try {
        classPath.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
