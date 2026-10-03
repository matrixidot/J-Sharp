package io.github.matrixidot.jsharp.lsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the server like an editor would, over piped streams. */
class LanguageServerTest {
  @TempDir Path dir;

  private PipedOutputStream toServer;
  private Transport client;
  private Thread serverThread;
  private final LinkedBlockingQueue<Map<String, Object>> fromServer = new LinkedBlockingQueue<>();
  private int nextId = 1;
  private final int[] exitCode = {-1};

  @BeforeEach
  void start() throws IOException {
    toServer = new PipedOutputStream();
    PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 20);
    PipedOutputStream serverOut = new PipedOutputStream();
    PipedInputStream clientIn = new PipedInputStream(serverOut, 1 << 20);
    client = new Transport(clientIn, toServer);
    LanguageServer server =
        new LanguageServer(
            serverIn,
            serverOut,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            List.of());
    serverThread =
        new Thread(
            () -> {
              try {
                exitCode[0] = server.run();
                serverOut.close();
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    serverThread.start();
    Thread reader =
        new Thread(
            () -> {
              try {
                String body;
                while ((body = client.read()) != null) {
                  @SuppressWarnings("unchecked")
                  Map<String, Object> m = (Map<String, Object>) Json.parse(body);
                  fromServer.add(m);
                }
              } catch (IOException e) {
                // stream closed
              }
            });
    reader.setDaemon(true);
    reader.start();
  }

  @AfterEach
  void stop() throws Exception {
    serverThread.join(5000);
  }

  private Object request(String method, Object params) throws Exception {
    int id = nextId++;
    client.write(
        Json.write(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
    while (true) {
      Map<String, Object> m = fromServer.poll(30, TimeUnit.SECONDS);
      assertThat(m).as("response to " + method).isNotNull();
      if (m.get("id") instanceof Number n && n.intValue() == id) {
        assertThat(m).doesNotContainKey("error");
        return m.get("result");
      }
    }
  }

  private void notifyServer(String method, Object params) throws IOException {
    client.write(Json.write(Json.obj("jsonrpc", "2.0", "method", method, "params", params)));
  }

  @SuppressWarnings("unchecked")
  private List<Object> nextDiagnostics(URI uri) throws InterruptedException {
    while (true) {
      Map<String, Object> m = fromServer.poll(30, TimeUnit.SECONDS);
      assertThat(m).as("diagnostics").isNotNull();
      if ("textDocument/publishDiagnostics".equals(m.get("method"))) {
        Map<String, Object> p = (Map<String, Object>) m.get("params");
        if (uri.toString().equals(p.get("uri"))) {
          return (List<Object>) p.get("diagnostics");
        }
      }
    }
  }

  private static Map<String, Object> pos(int line, int character) {
    return Json.obj("line", line, "character", character);
  }

  @Test
  @SuppressWarnings("unchecked")
  void editorSession() throws Exception {
    Path model = dir.resolve("model.jsharp");
    Files.writeString(
        model,
        """
        public class Counter {
            public int count { get; private set; }
            public void bump(int by = 1) { count += by; }
        }
        """);
    Path main = dir.resolve("main.jsharp");
    String text =
        """
        var c = new Counter();
        c.bump();
        int total = c.count;
        String bad = total;
        """;
    Files.writeString(main, text);
    URI uri = main.toUri();

    Map<String, Object> init =
        (Map<String, Object>) request("initialize", Json.obj("rootUri", dir.toUri().toString()));
    assertThat((Map<String, Object>) init.get("capabilities")).containsKey("hoverProvider");
    notifyServer("initialized", Json.obj());

    notifyServer(
        "textDocument/didOpen",
        Json.obj(
            "textDocument",
            Json.obj("uri", uri.toString(), "languageId", "jsharp", "version", 1, "text", text)));
    List<Object> diags = nextDiagnostics(uri);
    assertThat(diags).hasSize(1);
    Map<String, Object> d = (Map<String, Object>) diags.getFirst();
    assertThat(d.get("code")).isEqualTo("JS0600");
    assertThat(
            ((Map<String, Object>) ((Map<String, Object>) d.get("range")).get("start")).get("line"))
        .isEqualTo(3L);

    Map<String, Object> hover =
        (Map<String, Object>)
            request(
                "textDocument/hover",
                Json.obj("textDocument", Json.obj("uri", uri.toString()), "position", pos(1, 3)));
    String hoverText = (String) ((Map<String, Object>) hover.get("contents")).get("value");
    assertThat(hoverText).contains("void Counter.bump(int by = 1)");

    Map<String, Object> def =
        (Map<String, Object>)
            request(
                "textDocument/definition",
                Json.obj("textDocument", Json.obj("uri", uri.toString()), "position", pos(1, 3)));
    assertThat((String) def.get("uri")).endsWith("model.jsharp");
    assertThat(
            ((Map<String, Object>) ((Map<String, Object>) def.get("range")).get("start"))
                .get("line"))
        .isEqualTo(2L);

    List<Object> symbols =
        (List<Object>)
            request(
                "textDocument/documentSymbol",
                Json.obj("textDocument", Json.obj("uri", model.toUri().toString())));
    List<String> names = new ArrayList<>();
    symbols.forEach(s -> names.add((String) ((Map<String, Object>) s).get("name")));
    assertThat(names).contains("Counter", "count", "bump");

    // Completion after "c." on a new line.
    String edited = text + "c.\n";
    notifyServer(
        "textDocument/didChange",
        Json.obj(
            "textDocument", Json.obj("uri", uri.toString(), "version", 2),
            "contentChanges", List.of(Json.obj("text", edited))));
    nextDiagnostics(uri);
    Map<String, Object> completion =
        (Map<String, Object>)
            request(
                "textDocument/completion",
                Json.obj("textDocument", Json.obj("uri", uri.toString()), "position", pos(4, 2)));
    List<String> labels = new ArrayList<>();
    ((List<Object>) completion.get("items"))
        .forEach(i -> labels.add((String) ((Map<String, Object>) i).get("label")));
    assertThat(labels).contains("bump", "count", "toString", "hashCode");

    // Fixing the error clears the diagnostics.
    String fixed = text.replace("String bad = total;", "String good = $\"{total}\";");
    notifyServer(
        "textDocument/didChange",
        Json.obj(
            "textDocument", Json.obj("uri", uri.toString(), "version", 3),
            "contentChanges", List.of(Json.obj("text", fixed))));
    assertThat(nextDiagnostics(uri)).isEmpty();

    request("shutdown", null);
    notifyServer("exit", null);
    serverThread.join(5000);
    assertThat(exitCode[0]).isZero();
  }

  @Test
  void jsonRoundTrip() {
    Object v = Json.parse("{\"a\":[1,2.5,\"x\\n\\u0041\",true,null],\"b\":{}}");
    assertThat(Json.write(v)).isEqualTo("{\"a\":[1,2.5,\"x\\nA\",true,null],\"b\":{}}");
  }
}
