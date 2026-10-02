package dev.jsharp.cli;

import dev.jsharp.compiler.LanguageInfo;
import java.io.PrintStream;

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
    err.println(LanguageInfo.ID + ": unknown command '" + args[0] + "'");
    printUsage(err);
    return 2;
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
    out.println("  --version                                             print version");
  }
}
