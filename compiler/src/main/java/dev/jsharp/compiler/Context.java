package dev.jsharp.compiler;

import dev.jsharp.compiler.ast.CompilationUnit;
import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.diag.Diagnostic;
import dev.jsharp.compiler.diag.Diagnostics;
import dev.jsharp.compiler.diag.Severity;
import dev.jsharp.compiler.driver.CompilerOptions;
import dev.jsharp.compiler.resolve.FileScope;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.Symtab;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-compilation state shared by the phases: symbol table, diagnostics, options and file scopes.
 * Never shared between compilations (the {@link dev.jsharp.compiler.classpath.ClassPath} inside the
 * symbol table may be).
 */
public final class Context {
  public final Symtab syms;
  public final Diagnostics diags;
  public final CompilerOptions options;
  private final Map<CompilationUnit, FileScope> fileScopes = new IdentityHashMap<>();

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
