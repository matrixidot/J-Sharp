package dev.jsharp.compiler.driver;

import dev.jsharp.compiler.Context;
import dev.jsharp.compiler.LanguageInfo;
import dev.jsharp.compiler.ast.CompilationUnit;
import dev.jsharp.compiler.classpath.ClassPath;
import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.diag.Diagnostic;
import dev.jsharp.compiler.diag.Diagnostics;
import dev.jsharp.compiler.resolve.Enter;
import dev.jsharp.compiler.resolve.MemberEnter;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.Symtab;
import dev.jsharp.compiler.syntax.Parser;
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
    RuntimeLocator.find().ifPresent(cp::add);
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
          for (SourceFile f : sources) {
            currentFile = f;
            units.add(Parser.parse(f, diags));
          }
          currentFile = null;
          if (diags.hasErrors()) {
            return;
          }
          ctx = new Context(new Symtab(classPath), diags, options);
          memberEnter = new MemberEnter(ctx);
          enter = new Enter(ctx, memberEnter);
          enter.enterAll(units);
        });
  }

  /**
   * Runs {@code body}, converting unexpected compiler exceptions into an internal-error diagnostic
   * (never a stack trace for the user).
   */
  private boolean guarded(Runnable body) {
    try {
      body.run();
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
