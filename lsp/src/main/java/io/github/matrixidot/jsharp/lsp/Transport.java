package io.github.matrixidot.jsharp.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** The LSP base protocol: {@code Content-Length} framed JSON messages over a byte stream. */
final class Transport {
  private final InputStream in;
  private final OutputStream out;

  Transport(InputStream in, OutputStream out) {
    this.in = in;
    this.out = out;
  }

  /** Reads the next message body, or null at end of input. */
  String read() throws IOException {
    int length = -1;
    while (true) {
      String line = readHeaderLine();
      if (line == null) {
        return null;
      }
      if (line.isEmpty()) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
        length = Integer.parseInt(line.substring(colon + 1).trim());
      }
    }
    if (length < 0) {
      throw new IOException("message without Content-Length");
    }
    byte[] body = in.readNBytes(length);
    if (body.length < length) {
      return null;
    }
    return new String(body, StandardCharsets.UTF_8);
  }

  private String readHeaderLine() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') {
        String s = buf.toString(StandardCharsets.US_ASCII);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
      }
      buf.write(c);
    }
    return buf.size() == 0 ? null : buf.toString(StandardCharsets.US_ASCII);
  }

  /** Writes one message (thread-safe). */
  synchronized void write(String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }
}
