package io.github.matrixidot.jsharp.intellij;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

/** The J# server's requests beyond the standard protocol. */
public interface JSharpServer extends LanguageServer {
  /** The files {@code jsharp run} needs for the program in a document (its analysis unit). */
  @JsonRequest("jsharp/programFiles")
  CompletableFuture<List<String>> programFiles(DocumentParams params);

  /** {@code {"textDocument": {"uri": ...}}}. */
  final class DocumentParams {
    public TextDocumentIdentifier textDocument;

    public DocumentParams(TextDocumentIdentifier textDocument) {
      this.textDocument = textDocument;
    }
  }
}
