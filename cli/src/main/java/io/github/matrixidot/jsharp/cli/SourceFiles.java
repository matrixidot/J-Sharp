package io.github.matrixidot.jsharp.cli;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Expands command-line inputs (files and directories) into source files, sorted for determinism.
 */
final class SourceFiles {
  private SourceFiles() {}

  static List<SourceFile> collect(List<Path> inputs) throws IOException {
    List<Path> paths = new ArrayList<>();
    for (Path in : inputs) {
      if (Files.isDirectory(in)) {
        try (Stream<Path> s = Files.walk(in)) {
          s.filter(
                  p -> p.toString().endsWith(LanguageInfo.FILE_EXTENSION) && Files.isRegularFile(p))
              .sorted()
              .forEach(paths::add);
        }
      } else if (Files.isRegularFile(in)) {
        paths.add(in);
      } else {
        throw new NoSuchFileException(in.toString(), null, "no such file or directory");
      }
    }
    List<SourceFile> out = new ArrayList<>();
    for (Path p : paths) {
      out.add(SourceFile.read(p));
    }
    return out;
  }
}
