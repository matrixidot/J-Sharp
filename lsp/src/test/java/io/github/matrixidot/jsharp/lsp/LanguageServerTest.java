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

  /** The position of {@code needle} (plus {@code delta} characters) in {@code text}. */
  private static Map<String, Object> posOf(String text, String needle, int delta) {
    int off = text.indexOf(needle);
    assertThat(off).as(needle).isNotNegative();
    off += delta;
    int line = 0;
    int lineStart = 0;
    for (int i = 0; i < off; i++) {
      if (text.charAt(i) == '\n') {
        line++;
        lineStart = i + 1;
      }
    }
    return pos(line, off - lineStart);
  }

  /** The position right after the last occurrence of {@code needle}. */
  private static Map<String, Object> posAfterLast(String text, String needle) {
    int off = text.lastIndexOf(needle) + needle.length();
    String before = text.substring(0, off);
    int line = (int) before.chars().filter(c -> c == '\n').count();
    return pos(line, off - (before.lastIndexOf('\n') + 1));
  }

  private Map<String, Object> errorOf(String method, Object params) throws Exception {
    int id = nextId++;
    client.write(
        Json.write(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
    while (true) {
      Map<String, Object> m = fromServer.poll(30, TimeUnit.SECONDS);
      assertThat(m).as("response to " + method).isNotNull();
      if (m.get("id") instanceof Number n && n.intValue() == id) {
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) m.get("error");
        assertThat(error).as("error from " + method).isNotNull();
        return error;
      }
    }
  }

  private static Map<String, Object> at(URI uri, Map<String, Object> pos) {
    return Json.obj("textDocument", Json.obj("uri", uri.toString()), "position", pos);
  }

  @Test
  @SuppressWarnings("unchecked")
  void navigationRenameSignaturesAndRun() throws Exception {
    Path lib = dir.resolve("greetings.jsharp");
    String libText =
        """
        public String greet(String name, String greeting = "Hello") => $"{greeting}, {name}!";
        public class Counter {
            public Counter(int start) { }
            public void bump(int by) { }
        }
        """;
    Files.writeString(lib, libText);
    Path main = dir.resolve("main.jsharp");
    String text =
        """
        import java.util.*;
        int total(List<int> xs) {
            atomic var sum = 0;
            int sq(int x) => x * x;
            xs.forEach(x => sum += sq(x));
            return sum;
        }
        var count = 1;
        count = count + total([1, 2]);
        println(greet("Ada"));
        var c = new Counter(3);
        c.bump(count);
        """;
    Files.writeString(main, text);
    URI uri = main.toUri();
    request("initialize", Json.obj("rootUri", dir.toUri().toString()));
    notifyServer(
        "textDocument/didOpen",
        Json.obj(
            "textDocument",
            Json.obj("uri", uri.toString(), "languageId", "jsharp", "version", 1, "text", text)));
    assertThat(nextDiagnostics(uri)).isEmpty();

    // Hover: a local function shows as declared, an atomic local says so.
    Map<String, Object> hover =
        (Map<String, Object>) request("textDocument/hover", at(uri, posOf(text, "sq(x))", 0)));
    assertThat((String) ((Map<String, Object>) hover.get("contents")).get("value"))
        .contains("int sq(int x)")
        .doesNotContain("local$");
    hover = (Map<String, Object>) request("textDocument/hover", at(uri, posOf(text, "sum;", 0)));
    assertThat((String) ((Map<String, Object>) hover.get("contents")).get("value"))
        .contains("atomic var sum: int");

    // Go to definition of a local function.
    Map<String, Object> def =
        (Map<String, Object>) request("textDocument/definition", at(uri, posOf(text, "sq(x))", 0)));
    assertThat(((Map<String, Object>) ((Map<String, Object>) def.get("range")).get("start")))
        .containsEntry("line", 3L);

    // References include writes; highlights stay in the file.
    List<Object> refs =
        (List<Object>)
            request(
                "textDocument/references",
                Json.obj(
                    "textDocument", Json.obj("uri", uri.toString()),
                    "position", posOf(text, "count = 1", 0),
                    "context", Json.obj("includeDeclaration", true)));
    assertThat(refs).hasSize(4); // declaration, write, read, argument

    // Rename a top-level function across files.
    Map<String, Object> edit =
        (Map<String, Object>)
            request(
                "textDocument/rename",
                Json.obj(
                    "textDocument", Json.obj("uri", uri.toString()),
                    "position", posOf(text, "greet(", 1),
                    "newName", "welcome"));
    Map<String, Object> changes = (Map<String, Object>) edit.get("changes");
    assertThat(changes).containsKeys(uri.toString(), lib.toUri().toString());
    // Types and library members are refused with a reason.
    assertThat(errorOf("textDocument/prepareRename", at(uri, posOf(text, "Counter(3)", 0))))
        .containsEntry("message", "Renaming types is not supported yet.");
    assertThat(errorOf("textDocument/prepareRename", at(uri, posOf(text, "forEach", 0))))
        .containsEntry("message", "'forEach' is declared outside this project.");
    assertThat(
            errorOf(
                "textDocument/rename",
                Json.obj(
                    "textDocument", Json.obj("uri", uri.toString()),
                    "position", posOf(text, "count = 1", 0),
                    "newName", "if")))
        .containsEntry("message", "'if' is not a valid name.");

    // Signature help while typing an unclosed call: function, method, constructor.
    String[][] cases = {
      {"greet(\"x\", ", "greet(String name, String greeting = \"Hello\"): String", "1"},
      {"c.bump(", "bump(int by): void", "0"},
      {"new Counter(", "new Counter(int start)", "0"},
    };
    int version = 2;
    for (String[] k : cases) {
      String typing = text + k[0];
      notifyServer(
          "textDocument/didChange",
          Json.obj(
              "textDocument", Json.obj("uri", uri.toString(), "version", version++),
              "contentChanges", List.of(Json.obj("text", typing))));
      nextDiagnostics(uri);
      Map<String, Object> sig =
          (Map<String, Object>)
              request("textDocument/signatureHelp", at(uri, posAfterLast(typing, k[0])));
      assertThat(sig).as(k[0]).isNotNull();
      assertThat(
              ((Map<String, Object>) ((List<Object>) sig.get("signatures")).getFirst())
                  .get("label"))
          .isEqualTo(k[1]);
      assertThat(sig.get("activeParameter")).isEqualTo(Long.parseLong(k[2]));
    }

    // A Run lens on the program, and the files it needs.
    List<Object> lenses =
        (List<Object>)
            request(
                "textDocument/codeLens", Json.obj("textDocument", Json.obj("uri", uri.toString())));
    assertThat(lenses).hasSize(1);
    List<Object> files =
        (List<Object>)
            request(
                "jsharp/programFiles", Json.obj("textDocument", Json.obj("uri", uri.toString())));
    assertThat(files).hasSize(2);

    request("shutdown", null);
    notifyServer("exit", null);
  }

  @Test
  void jsonRoundTrip() {
    Object v = Json.parse("{\"a\":[1,2.5,\"x\\n\\u0041\",true,null],\"b\":{}}");
    assertThat(Json.write(v)).isEqualTo("{\"a\":[1,2.5,\"x\\nA\",true,null],\"b\":{}}");
  }
}
