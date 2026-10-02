package dev.jsharp.cli;

import dev.jsharp.compiler.LanguageInfo;
import dev.jsharp.compiler.ast.AstPrinter;
import dev.jsharp.compiler.ast.CompilationUnit;
import dev.jsharp.compiler.diag.DiagnosticRenderer;
import dev.jsharp.compiler.diag.Diagnostics;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.syntax.Parser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/** Entry point of the {@code jsharp} command-line tool. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Runs the CLI and returns the process exit code. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
      printUsage(out);
      return args.length == 0 ? 2 : 0;
    }
    if (args[0].equals("--version") || args[0].equals("-V")) {
      out.println(LanguageInfo.ID + " " + LanguageInfo.VERSION);
      return 0;
    }
    if (args[0].equals("parse") && args.length == 2) {
      return parse(Path.of(args[1]), out, err);
    }
    err.println(LanguageInfo.ID + ": unknown command '" + args[0] + "'");
    printUsage(err);
    return 2;
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

  private static void printUsage(PrintStream out) {
    out.println("usage: " + LanguageInfo.ID + " <command> [options]");
    out.println();
    out.println("commands:");
    out.println("  build <src...> -d <dir> [-cp <path>] [--jar <file>]   compile sources");
    out.println(
        "  run <file"
            + LanguageInfo.FILE_EXTENSION
            + "> [args...]                    compile and run");
    out.println("  check <src...>                                        report diagnostics only");
    out.println(
        "  parse <file>                                          print the syntax tree (debug)");
    out.println("  --version                                             print version");
  }
}
