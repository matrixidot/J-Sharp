package io.github.matrixidot.jsharp.cli;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.ast.AstPrinter;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.classpath.ClassPath;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticRenderer;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostics;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.syntax.Parser;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Entry point of the {@code jsharp} command-line tool. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int code = run(args, System.out, System.err);
    if (code != 0) {
      System.exit(code);
    }
  }

  /** Parsed common options. */
  private static final class Options {
    final List<Path> inputs = new ArrayList<>();
    final List<Path> classPath = new ArrayList<>();
    Path outDir;
    Path jar;
    boolean includeRuntime;
    boolean json;
    boolean strictNullness;
    boolean warningsAsErrors;
    List<String> programArgs = List.of();
  }

  /** Runs the CLI and returns the process exit code. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
      printUsage(out);
      return args.length == 0 ? 2 : 0;
    }
    if (args[0].equals("--cds-train")) {
      return cdsTrain();
    }
    if (args[0].equals("--version") || args[0].equals("-V")) {
      out.println(LanguageInfo.ID + " " + LanguageInfo.VERSION);
      return 0;
    }
    String cmd = args[0];
    String[] rest = Arrays.copyOfRange(args, 1, args.length);
    try {
      return switch (cmd) {
        case "parse" ->
            rest.length == 1
                ? parse(Path.of(rest[0]), out, err)
                : usageError(err, "parse takes one file");
        case "check" -> check(rest, out, err);
        case "build" -> build(rest, out, err);
        case "run" -> runProgram(rest, out, err);
        case "lsp" -> languageServer(rest, err);
        default -> {
          err.println(LanguageInfo.ID + ": unknown command '" + cmd + "'");
          printUsage(err);
          yield 2;
        }
      };
    } catch (UsageException e) {
      return usageError(err, e.getMessage());
    }
  }

  private static final class UsageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UsageException(String m) {
      super(m);
    }
  }

  private static int usageError(PrintStream err, String msg) {
    err.println(LanguageInfo.ID + ": " + msg);
    return 2;
  }

  /** Parses options; for {@code run}, everything after the first source file is program args. */
  private static Options parseOptions(String[] args, boolean isRun) {
    Options o = new Options();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      switch (a) {
        case "-d" -> o.outDir = Path.of(need(args, ++i, a));
        case "-cp", "--class-path" -> o.classPath.addAll(ClassPath.split(need(args, ++i, a)));
        case "--jar" -> o.jar = Path.of(need(args, ++i, a));
        case "--include-runtime" -> o.includeRuntime = true;
        case "--diagnostics=json" -> o.json = true;
        case "--strict-platform-nullness" -> o.strictNullness = true;
        case "-Werror" -> o.warningsAsErrors = true;
        default -> {
          if (a.startsWith("-") && o.inputs.isEmpty()) {
            throw new UsageException("unknown option " + a);
          }
          o.inputs.add(Path.of(a));
          if (isRun) {
            o.programArgs = List.of(Arrays.copyOfRange(args, i + 1, args.length));
            return o;
          }
        }
      }
    }
    return o;
  }

  private static String need(String[] args, int i, String opt) {
    if (i >= args.length) {
      throw new UsageException(opt + " needs a value");
    }
    return args[i];
  }

  private static CompilerOptions compilerOptions(Options o) {
    return new CompilerOptions(o.classPath, o.outDir, o.warningsAsErrors, o.strictNullness, true);
  }

  /** Compiles; prints diagnostics; returns the compilation or null if sources could not be read. */
  private static Compilation compileAll(
      Options o, PrintStream out, PrintStream err, boolean generate) {
    List<SourceFile> files;
    try {
      files = SourceFiles.collect(o.inputs);
    } catch (IOException e) {
      err.println(LanguageInfo.ID + ": " + e.getMessage());
      return null;
    }
    if (files.isEmpty()) {
      err.println(LanguageInfo.ID + ": no " + LanguageInfo.FILE_EXTENSION + " files given");
      return null;
    }
    Compilation comp = new Compilation(files, compilerOptions(o), null);
    if (generate) {
      comp.compile();
    } else {
      comp.analyze();
    }
    var ds = comp.diagnostics().sorted();
    if (o.json) {
      out.println(DiagnosticRenderer.renderJson(ds));
    } else if (!ds.isEmpty()) {
      err.print(new DiagnosticRenderer(System.console() != null).renderAll(ds));
    }
    return comp;
  }

  private static int check(String[] args, PrintStream out, PrintStream err) {
    Options o = parseOptions(args, false);
    Compilation comp = compileAll(o, out, err, false);
    if (comp == null) {
      return 2;
    }
    comp.close();
    return comp.diagnostics().hasErrors() ? 1 : 0;
  }

  private static int build(String[] args, PrintStream out, PrintStream err) {
    Options o = parseOptions(args, false);
    if (o.outDir == null && o.jar == null) {
      o.outDir = Path.of("out");
    }
    Compilation comp = compileAll(o, out, err, true);
    if (comp == null) {
      return 2;
    }
    comp.close();
    if (comp.diagnostics().hasErrors()) {
      return 1;
    }
    if (o.jar != null) {
      try {
        Jars.write(
            o.jar,
            comp.classFiles(),
            comp.mainClass(),
            o.includeRuntime
                ? io.github.matrixidot.jsharp.compiler.driver.RuntimeLocator.findAll()
                : List.of());
      } catch (IOException e) {
        err.println(LanguageInfo.ID + ": cannot write " + o.jar + ": " + e.getMessage());
        return 2;
      }
    }
    return 0;
  }

  /** Compiles in memory and runs the entry point in this JVM. */
  private static int runProgram(String[] args, PrintStream out, PrintStream err) {
    Options o = parseOptions(args, true);
    Compilation comp = compileAll(o, out, err, true);
    if (comp == null) {
      return 2;
    }
    comp.close();
    if (comp.diagnostics().hasErrors()) {
      return 1;
    }
    if (comp.mainClass() == null) {
      err.println(
          LanguageInfo.ID + ": no entry point (top-level statements or a static main(String[]))");
      return 2;
    }
    List<java.net.URL> urls = new ArrayList<>();
    for (Path p : o.classPath) {
      try {
        urls.add(p.toUri().toURL());
      } catch (java.net.MalformedURLException e) {
        throw new UsageException("bad class path entry " + p);
      }
    }
    var parent =
        new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), Main.class.getClassLoader());
    var loader = new MemoryClassLoader(comp.classFiles(), parent);
    try {
      Class<?> main = Class.forName(comp.mainClass(), true, loader);
      Method m = main.getMethod("main", String[].class);
      m.invoke(null, (Object) o.programArgs.toArray(String[]::new));
      return 0;
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      err.println("Exception in thread \"main\" " + cause);
      for (StackTraceElement el : cause.getStackTrace()) {
        if (el.getClassName().startsWith("java.lang.reflect")
            || el.getClassName().startsWith("jdk.internal.reflect")) {
          break;
        }
        err.println("\tat " + el);
      }
      return 1;
    } catch (ReflectiveOperationException | LinkageError e) {
      err.println(LanguageInfo.ID + ": cannot run " + comp.mainClass() + ": " + e);
      return 1;
    }
  }

  /**
   * Runs the language server on stdin/stdout ({@code jsharp lsp [-cp path]}); logs go to stderr.
   */
  private static int languageServer(String[] args, PrintStream err) {
    Options o = parseOptions(args, false);
    try {
      return new io.github.matrixidot.jsharp.lsp.LanguageServer(
              System.in, System.out, err, o.classPath)
          .run();
    } catch (IOException e) {
      err.println(LanguageInfo.ID + " lsp: " + e.getMessage());
      return 1;
    }
  }

  /** Loads compiled classes from memory. */
  static final class MemoryClassLoader extends ClassLoader {
    private final Map<String, byte[]> classes;

    MemoryClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
      super(parent);
      this.classes = classes;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      byte[] b = classes.get(name.replace('.', '/'));
      if (b == null) {
        throw new ClassNotFoundException(name);
      }
      return defineClass(name, b, 0, b.length);
    }
  }

  /** Debug command: parse one file and print its syntax tree and syntax diagnostics. */
  private static int parse(Path file, PrintStream out, PrintStream err) {
    SourceFile src;
    try {
      src = SourceFile.read(file);
    } catch (IOException e) {
      err.println(LanguageInfo.ID + ": cannot read " + file + ": " + e.getMessage());
      return 2;
    }
    Diagnostics diags = new Diagnostics();
    CompilationUnit unit = Parser.parse(src, diags);
    out.print(AstPrinter.print(unit));
    err.print(new DiagnosticRenderer(System.console() != null).renderAll(diags.sorted()));
    return diags.hasErrors() ? 1 : 0;
  }

  /**
   * Hidden command run once by the launcher to record a class-data-sharing archive: compiles a
   * small program that touches the parser, checker, Java class loading, lowering and codegen.
   */
  private static int cdsTrain() {
    String program =
        """
        import java.util.*;
        import java.util.function.*;
        public sealed interface Shape permits Circle, Square;
        public record Circle(double r) : Shape;
        public record Square(double s) : Shape;
        public enum Color { Red, Green }
        public class Box<T : Comparable<T>> {
            public T value { get; set; }
            public Box(T value) { this.value = value; }
            public boolean bigger(Box<T> o) => value.compareTo(o.value) > 0;
        }
        public static double area(Shape s) => s switch {
            Circle c => Math.PI * c.r * c.r,
            Square(var side) => side * side,
        };
        public static async Task<int> later(int x) => x + 1;
        var shapes = List.of(new Circle(1), new Square(2));
        var names = shapes.where(s => area(s) > 1).select(s => s.toString()).toList();
        Map<String, Integer> counts = new HashMap<>();
        foreach (var n in names) counts.merge(n, 1, Integer::sum);
        String? maybe = names.firstOrNull();
        println($"{names.size()} {maybe?.length() ?? 0} {counts} {area(shapes.get(0)):F2}");
        var sb = new StringBuilder();
        for (int i = 0; i < 3; i++) sb.append(i);
        try { println(sb.toString() + new Box<String>("a").bigger(new Box<>("b"))); }
        catch (RuntimeException e) { println(e.getMessage()); }
        Function<int, int> sq = x => x * x;
        println(sq.apply(3) + later(1).join() + range(0, 4).sum());
        """;
    Compilation comp =
        new Compilation(
            List.of(new SourceFile("train.jsharp", program)), CompilerOptions.defaults(), null);
    comp.compile();
    comp.close();
    return comp.diagnostics().hasErrors() ? 1 : 0;
  }

  private static void printUsage(PrintStream out) {
    out.println("usage: " + LanguageInfo.ID + " <command> [options]");
    out.println();
    out.println("commands:");
    out.println("  run <file|dir...> [args...]          compile in memory and run the entry point");
    out.println(
        "  build <src...> [-d dir] [--jar f]    compile to class files (default ./out) or a jar");
    out.println(
        "        [--include-runtime]            put the J# runtime in the jar (java -jar f)");
    out.println("  check <src...>                       report diagnostics only");
    out.println(
        "  lsp [-cp path]                       language server on stdin/stdout (for editors)");
    out.println("  parse <file>                         print the syntax tree (debug)");
    out.println("  --version                            print version");
    out.println();
    out.println("options:");
    out.println("  -cp, --class-path <path>             extra class path (jars and directories)");
    out.println("  --diagnostics=json                   machine-readable diagnostics");
    out.println("  --strict-platform-nullness           warn on member access through Java types");
    out.println("  -Werror                              treat warnings as errors");
    if (Files.exists(Path.of("."))) {
      out.flush();
    }
  }
}
