package io.github.matrixidot.jsharp.compiler;

import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostics;
import io.github.matrixidot.jsharp.compiler.diag.Severity;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.resolve.FileScope;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-compilation state shared by the phases: symbol table, diagnostics, options and file scopes.
 * Never shared between compilations (the {@link
 * io.github.matrixidot.jsharp.compiler.classpath.ClassPath} inside the symbol table may be).
 */
public final class Context {
  public final Symtab syms;
  public final Diagnostics diags;
  public final CompilerOptions options;
  private final Map<CompilationUnit, FileScope> fileScopes = new IdentityHashMap<>();

  /**
   * Editor tooling: the class or type variable each resolved type name denotes, by file and the
   * span of the name; null unless recording (see {@code Compilation.recordExpressionTypes}).
   */
  public Map<SourceFile, Map<Span, io.github.matrixidot.jsharp.compiler.symbols.Symbol>> typeRefs;

  /** Records that the name at {@code span} denotes {@code sym} (no-op unless recording). */
  public void recordTypeRef(
      SourceFile file, Span span, io.github.matrixidot.jsharp.compiler.symbols.Symbol sym) {
    if (typeRefs != null && file != null && span != null && sym != null) {
      typeRefs.computeIfAbsent(file, f -> new java.util.HashMap<>()).putIfAbsent(span, sym);
    }
  }

  public Context(Symtab syms, Diagnostics diags, CompilerOptions options) {
    this.syms = syms;
    this.diags = diags;
    this.options = options;
  }

  public FileScope fileScope(CompilationUnit unit) {
    return fileScopes.get(unit);
  }

  public void setFileScope(CompilationUnit unit, FileScope scope) {
    fileScopes.put(unit, scope);
  }

  /** Starts an error; finish with {@code .report(ctx.diags)} after adding notes/help. */
  public Diagnostic.Builder error(Code code, SourceFile file, Span span, String message) {
    return Diagnostic.error(code, file, span, message);
  }

  /** Reports an error with no extras. */
  public void report(Code code, SourceFile file, Span span, String message) {
    diags.report(Diagnostic.error(code, file, span, message).build());
  }

  /** Reports a warning. */
  public void warn(Code code, SourceFile file, Span span, String message) {
    diags.report(Diagnostic.error(code, file, span, message).severity(Severity.WARNING).build());
  }
}
