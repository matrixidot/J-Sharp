package dev.jsharp.compiler.classpath;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A searchable list of class file locations: the JDK's {@code jrt:/} image, directories and jars.
 * Thread-safe and immutable after construction, so one instance may be shared by many compilations
 * (e.g. a test suite) to amortize JDK image setup.
 */
public final class ClassPath implements AutoCloseable {

  /** One location that can supply class files. */
  public interface Entry extends AutoCloseable {
    /** Returns the class file bytes for an internal name, or null. */
    byte[] read(String internalName) throws IOException;

    /** True if the entry contains the package (internal form, e.g. {@code java/util}). */
    boolean hasPackage(String pkg);

    /** Simple binary names (without {@code .class}) of classes directly in a package. */
    List<String> list(String pkg) throws IOException;

    /** True for the JDK image (whose packages are never scanned for extension methods). */
    default boolean isJdk() {
      return false;
    }

    @Override
    default void close() throws IOException {}
  }

  private final List<Entry> entries;
  private final Map<String, Boolean> packageCache = new ConcurrentHashMap<>();

  private ClassPath(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /**
   * Creates a class path of the running JDK plus the given directories/jars.
   *
   * @param userEntries directories and jar files, in search order
   */
  public static ClassPath of(List<Path> userEntries) throws IOException {
    List<Entry> es = new ArrayList<>();
    es.add(JrtEntry.create());
    for (Path p : userEntries) {
      if (Files.isDirectory(p)) {
        es.add(new DirEntry(p));
      } else if (Files.isRegularFile(p)) {
        es.add(new JarEntry(p));
      }
      // Missing entries are ignored, like javac.
    }
    return new ClassPath(es);
  }

  /** Parses a platform path-separator separated class path string. */
  public static List<Path> split(String cp) {
    List<Path> out = new ArrayList<>();
    if (cp == null || cp.isBlank()) {
      return out;
    }
    for (String s : cp.split(java.io.File.pathSeparator)) {
      if (!s.isBlank()) {
        out.add(Path.of(s));
      }
    }
    return out;
  }

  /** Returns class file bytes, or null if no entry has the class. */
  public byte[] read(String internalName) {
    for (Entry e : entries) {
      try {
        byte[] b = e.read(internalName);
        if (b != null) {
          return b;
        }
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
    return null;
  }

  /** True if any entry contains the package (dotted form). */
  public boolean hasPackage(String dottedPkg) {
    String internal = dottedPkg.replace('.', '/');
    return packageCache.computeIfAbsent(
        internal,
        k -> {
          for (Entry e : entries) {
            if (e.hasPackage(k)) {
              return true;
            }
          }
          return false;
        });
  }

  /** Simple binary names of classes in a package across all entries (sorted, deduplicated). */
  public Set<String> list(String dottedPkg, boolean includeJdk) {
    Set<String> out = new TreeSet<>();
    String internal = dottedPkg.replace('.', '/');
    for (Entry e : entries) {
      if (e.isJdk() && !includeJdk) {
        continue;
      }
      try {
        out.addAll(e.list(internal));
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
    return out;
  }

  @Override
  public void close() throws IOException {
    for (Entry e : entries) {
      e.close();
    }
  }

  // ------------------------------------------------------------------ entries

  /** The JDK runtime image. */
  static final class JrtEntry implements Entry {
    private final FileSystem fs;

    /** package (internal form) -> module names containing it. */
    private final Map<String, List<String>> packages;

    private JrtEntry(FileSystem fs, Map<String, List<String>> packages) {
      this.fs = fs;
      this.packages = packages;
    }

    static JrtEntry create() throws IOException {
      FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
      Map<String, List<String>> pkgs = new HashMap<>();
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(fs.getPath("/packages"))) {
        for (Path pkgDir : ds) {
          String dotted = pkgDir.getFileName().toString();
          List<String> mods = new ArrayList<>();
          try (DirectoryStream<Path> ms = Files.newDirectoryStream(pkgDir)) {
            for (Path m : ms) {
              mods.add(m.getFileName().toString());
            }
          }
          pkgs.put(dotted.replace('.', '/'), mods);
        }
      }
      return new JrtEntry(fs, pkgs);
    }

    @Override
    public byte[] read(String internalName) throws IOException {
      int slash = internalName.lastIndexOf('/');
      String pkg = slash < 0 ? "" : internalName.substring(0, slash);
      List<String> mods = packages.get(pkg);
      if (mods == null) {
        return null;
      }
      for (String m : mods) {
        Path p = fs.getPath("/modules", m, internalName + ".class");
        if (Files.exists(p)) {
          return Files.readAllBytes(p);
        }
      }
      return null;
    }

    @Override
    public boolean hasPackage(String pkg) {
      return packages.containsKey(pkg);
    }

    @Override
    public List<String> list(String pkg) throws IOException {
      List<String> out = new ArrayList<>();
      List<String> mods = packages.get(pkg);
      if (mods == null) {
        return out;
      }
      for (String m : mods) {
        Path dir = fs.getPath("/modules", m, pkg);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.class")) {
          for (Path p : ds) {
            String n = p.getFileName().toString();
            out.add(n.substring(0, n.length() - 6));
          }
        }
      }
      return out;
    }

    @Override
    public boolean isJdk() {
      return true;
    }
  }

  /** A directory of class files. */
  static final class DirEntry implements Entry {
    private final Path root;

    DirEntry(Path root) {
      this.root = root;
    }

    @Override
    public byte[] read(String internalName) throws IOException {
      Path p = root.resolve(internalName + ".class");
      return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    @Override
    public boolean hasPackage(String pkg) {
      return pkg.isEmpty() || Files.isDirectory(root.resolve(pkg));
    }

    @Override
    public List<String> list(String pkg) throws IOException {
      List<String> out = new ArrayList<>();
      Path dir = pkg.isEmpty() ? root : root.resolve(pkg);
      if (!Files.isDirectory(dir)) {
        return out;
      }
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.class")) {
        for (Path p : ds) {
          String n = p.getFileName().toString();
          out.add(n.substring(0, n.length() - 6));
        }
      }
      return out;
    }
  }

  /** A jar (zip) file. */
  static final class JarEntry implements Entry {
    private final ZipFile zip;
    private final Set<String> packages = new java.util.HashSet<>();

    JarEntry(Path jar) throws IOException {
      this.zip = new ZipFile(jar.toFile());
      var it = zip.entries();
      while (it.hasMoreElements()) {
        String n = it.nextElement().getName();
        int slash = n.lastIndexOf('/');
        if (n.endsWith(".class")) {
          packages.add(slash < 0 ? "" : n.substring(0, slash));
        }
      }
    }

    @Override
    public byte[] read(String internalName) throws IOException {
      ZipEntry e = zip.getEntry(internalName + ".class");
      if (e == null) {
        return null;
      }
      try (InputStream in = zip.getInputStream(e)) {
        return in.readAllBytes();
      }
    }

    @Override
    public boolean hasPackage(String pkg) {
      return packages.contains(pkg);
    }

    @Override
    public List<String> list(String pkg) {
      List<String> out = new ArrayList<>();
      String prefix = pkg.isEmpty() ? "" : pkg + "/";
      var it = zip.entries();
      while (it.hasMoreElements()) {
        String n = it.nextElement().getName();
        if (n.startsWith(prefix) && n.endsWith(".class") && n.indexOf('/', prefix.length()) < 0) {
          out.add(n.substring(prefix.length(), n.length() - 6));
        }
      }
      return out;
    }

    @Override
    public void close() throws IOException {
      zip.close();
    }
  }
}
