package io.github.matrixidot.jsharp.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Writes compiled classes into a jar with a manifest (deterministic entry order and times). */
final class Jars {
  private Jars() {}

  static void write(Path jar, Map<String, byte[]> classes, String mainClass) throws IOException {
    Manifest mf = new Manifest();
    mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    mf.getMainAttributes().put(new Attributes.Name("Created-By"), "jsharp");
    if (mainClass != null) {
      mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
    }
    if (jar.getParent() != null) {
      Files.createDirectories(jar.getParent());
    }
    try (OutputStream os = Files.newOutputStream(jar);
        JarOutputStream jos = new JarOutputStream(os, mf)) {
      for (var e : new TreeMap<>(classes).entrySet()) {
        JarEntry entry = new JarEntry(e.getKey() + ".class");
        entry.setTime(0);
        jos.putNextEntry(entry);
        jos.write(e.getValue());
        jos.closeEntry();
      }
    }
  }
}
