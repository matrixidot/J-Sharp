package dev.jsharp.compiler.driver;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;

/** Finds the J# runtime library (jar or class directory) visible to the compiler's class loader. */
public final class RuntimeLocator {
  private static final String PROBE = "jsharp/core/Prelude.class";

  private RuntimeLocator() {}

  public static Optional<Path> find() {
    URL url = RuntimeLocator.class.getClassLoader().getResource(PROBE);
    if (url == null) {
      return Optional.empty();
    }
    try {
      String s = url.toString();
      if (s.startsWith("jar:file:")) {
        String jar = s.substring("jar:".length(), s.indexOf("!/"));
        return Optional.of(Path.of(new java.net.URI(jar)));
      }
      if (s.startsWith("file:")) {
        Path p = Path.of(url.toURI());
        // strip jsharp/core/Prelude.class
        return Optional.of(p.getParent().getParent().getParent());
      }
    } catch (URISyntaxException | IllegalArgumentException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }
}
