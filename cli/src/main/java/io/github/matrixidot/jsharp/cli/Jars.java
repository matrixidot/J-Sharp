package io.github.matrixidot.jsharp.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Writes compiled classes into a jar with a manifest (deterministic entry order and times). */
final class Jars {
  private Jars() {}

  /**
   * Writes {@code classes} (internal name to bytes) and, if given, every class file from the
   * runtime roots (jars or class directories), so that {@code java -jar} needs nothing else.
   */
  static void write(Path jar, Map<String, byte[]> classes, String mainClass, List<Path> runtime)
      throws IOException {
    Manifest mf = new Manifest();
    mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    mf.getMainAttributes().put(new Attributes.Name("Created-By"), "jsharp");
    if (mainClass != null) {
      mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
    }
    TreeMap<String, byte[]> entries = new TreeMap<>();
    for (Path root : runtime) {
      collect(root, entries);
    }
    for (var e : classes.entrySet()) {
      entries.put(e.getKey() + ".class", e.getValue());
    }
    if (jar.getParent() != null) {
      Files.createDirectories(jar.getParent());
    }
    try (OutputStream os = Files.newOutputStream(jar);
        JarOutputStream jos = new JarOutputStream(os, mf)) {
      for (var e : entries.entrySet()) {
        JarEntry entry = new JarEntry(e.getKey());
        entry.setTime(0);
        jos.putNextEntry(entry);
        jos.write(e.getValue());
        jos.closeEntry();
      }
    }
  }

  private static void collect(Path root, Map<String, byte[]> out) throws IOException {
    if (Files.isDirectory(root)) {
      try (Stream<Path> s = Files.walk(root)) {
        for (Path p : s.filter(x -> x.toString().endsWith(".class")).toList()) {
          out.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
      }
      return;
    }
    try (JarFile jf = new JarFile(root.toFile())) {
      for (JarEntry e : jf.stream().toList()) {
        if (!e.isDirectory() && e.getName().endsWith(".class")) {
          try (InputStream in = jf.getInputStream(e)) {
            out.put(e.getName(), in.readAllBytes());
          }
        }
      }
    }
  }
}
