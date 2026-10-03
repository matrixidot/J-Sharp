package io.github.matrixidot.jsharp.compiler.driver;

import io.github.matrixidot.jsharp.compiler.Context;
import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.bound.BClass;
import io.github.matrixidot.jsharp.compiler.check.Attr;
import io.github.matrixidot.jsharp.compiler.check.ClassChecker;
import io.github.matrixidot.jsharp.compiler.classpath.ClassPath;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostics;
import io.github.matrixidot.jsharp.compiler.resolve.Enter;
import io.github.matrixidot.jsharp.compiler.resolve.MemberEnter;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import io.github.matrixidot.jsharp.compiler.syntax.Parser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    List<Path> cp = new ArrayList<>();
    cp.addAll(RuntimeLocator.findAll());
    cp.addAll(options.classPath());
    try {
      return ClassPath.of(cp);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Diagnostics diagnostics() {
    return diags;
  }

  public Context context() {
    return ctx;
  }

  public List<CompilationUnit> units() {
    return units;
  }

  public Enter enter() {
    return enter;
  }

  public MemberEnter memberEnter() {
    return memberEnter;
  }

  /** Runs parsing and semantic analysis. Returns true if there were no errors. */
  public boolean analyze() {
    return guarded(
        () -> {
          long t = System.nanoTime();
          for (SourceFile f : sources) {
            currentFile = f;
            units.add(Parser.parse(f, diags));
          }
          currentFile = null;
          t = phase("parse", t);
          if (diags.hasErrors()) {
            return;
          }
          ctx = new Context(new Symtab(classPath), diags, options);
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
          phase("check", t);
        });
  }

  private final java.util.Map<String, byte[]> classFiles = new java.util.LinkedHashMap<>();
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
          var lowerer =
              new io.github.matrixidot.jsharp.compiler.lower.Lowerer(
                  ctx, types, attr.anonymousSuperConstructors());
          List<BClass> lowered = lowerer.lowerAll(checked);
          t = phase("lower", t);
          java.util.Map<
                  io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol,
                  List<io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol>>
              nests = new java.util.IdentityHashMap<>();
          for (BClass c : lowered) {
            if (c.sym().outer() != null) {
              nests.computeIfAbsent(c.sym().outermost(), k -> new ArrayList<>()).add(c.sym());
            }
          }
          var gen =
              new io.github.matrixidot.jsharp.compiler.codegen.ClassGen(
                  types, options.emitDebugInfo(), nests);
          for (BClass c : lowered) {
            currentFile =
                c.sym().outermost().unit() != null ? c.sym().outermost().unit().file() : null;
            classFiles.put(c.sym().binaryName(), gen.generate(c));
            for (var m : c.methods()) {
              if (m.sym().has(io.github.matrixidot.jsharp.compiler.symbols.Flags.ENTRY_POINT)
                  || mainClass == null && isMain(m.sym())) {
                mainClass = c.sym().binaryName().replace('/', '.');
              }
            }
          }
          currentFile = null;
          t = phase("codegen", t);
          if (options.outputDir() != null) {
            writeClassFiles(options.outputDir());
            phase("write", t);
          }
        });
  }

  private static boolean isMain(io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol m) {
    return m.name().equals("main")
        && m.isStatic()
        && m.params().size() == 1
        && m.params().getFirst().type()
            instanceof io.github.matrixidot.jsharp.compiler.types.Type.ArrayType;
  }

  private void writeClassFiles(Path dir) {
    try {
      for (var e : classFiles.entrySet()) {
        Path out = dir.resolve(e.getKey() + ".class");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.write(out, e.getValue());
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private io.github.matrixidot.jsharp.compiler.ide.SourceIndex index;
  private boolean recordTypes;
  private final java.util.Map<
          SourceFile,
          java.util.Map<
              io.github.matrixidot.jsharp.compiler.source.Span,
              io.github.matrixidot.jsharp.compiler.types.Type>>
      recordedTypes = new java.util.IdentityHashMap<>();

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
  public io.github.matrixidot.jsharp.compiler.ide.SourceIndex index() {
    if (index == null) {
      index =
          io.github.matrixidot.jsharp.compiler.ide.SourceIndex.build(
              checked == null ? List.of() : checked, recordedTypes);
    }
    return index;
  }

  /** A class by JVM internal name ({@code java/util/List}), or null (needs a finished analysis). */
  public io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol lookupClass(String binaryName) {
    return ctx == null ? null : ctx.syms.lookup(binaryName);
  }

  /** Generated class files by JVM internal name. */
  public java.util.Map<String, byte[]> classFiles() {
    return classFiles;
  }

  /** The class containing the entry point (top-level statements or main), or null. */
  public String mainClass() {
    return mainClass;
  }

  /** Checked classes (after a successful {@link #analyze()}). */
  public List<BClass> checkedClasses() {
    return checked;
  }

  public Attr attr() {
    return attr;
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
