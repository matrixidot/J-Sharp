package io.github.matrixidot.jsharp.compiler.driver;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the J# runtime library visible to the compiler's class loader. The runtime is one jar when
 * installed, but in a development build its Java and J# parts are separate class directories, so
 * every root that holds a probe class is returned.
 */
public final class RuntimeLocator {
  private static final List<String> PROBES =
      List.of(
          "jsharp/core/Prelude.class",
          "jsharp/collections/Sequences.class",
          "jsharp/text/Strings.class");

  private RuntimeLocator() {}

  /** The root holding {@code jsharp.core.Prelude}, if any. */
  public static Optional<Path> find() {
    return root(PROBES.getFirst());
  }

  /** All distinct roots (jars or class directories) holding parts of the runtime. */
  public static List<Path> findAll() {
    List<Path> out = new ArrayList<>();
    for (String probe : PROBES) {
      root(probe).filter(p -> !out.contains(p)).ifPresent(out::add);
    }
    return out;
  }

  private static Optional<Path> root(String probe) {
    URL url = RuntimeLocator.class.getClassLoader().getResource(probe);
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
        for (int i = 0; i < probe.split("/").length; i++) {
          p = p.getParent();
        }
        return Optional.of(p);
      }
    } catch (URISyntaxException | IllegalArgumentException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }
}
