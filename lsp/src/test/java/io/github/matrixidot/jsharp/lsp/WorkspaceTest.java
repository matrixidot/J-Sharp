package io.github.matrixidot.jsharp.lsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkspaceTest {
  @Test
  void sourceRootStripsMatchingPackageDirectories() {
    Path f = Path.of("/p/src/ledger/report/x.jsharp");
    assertThat(Workspace.sourceRoot(f, "ledger.report")).isEqualTo(Path.of("/p/src"));
    assertThat(Workspace.sourceRoot(Path.of("/p/src/report/x.jsharp"), "ledger.report"))
        .isEqualTo(Path.of("/p/src"));
    assertThat(Workspace.sourceRoot(Path.of("/p/src/x.jsharp"), "ledger"))
        .isEqualTo(Path.of("/p/src"));
    assertThat(Workspace.sourceRoot(Path.of("/p/src/x.jsharp"), "")).isEqualTo(Path.of("/p/src"));
  }

  @Test
  void positionsRoundTrip() {
    String text = "ab\ncde\n\nf";
    assertThat(Workspace.offset(text, 1, 2)).isEqualTo(5);
    assertThat(Workspace.position(text, 5)).containsEntry("line", 1).containsEntry("character", 2);
    assertThat(Workspace.offset(text, 1, 99)).isEqualTo(6); // clamped to the line end
    assertThat(Workspace.offset(text, 3, 0)).isEqualTo(8);
  }

  @Test
  void detectsTopLevelStatements() {
    assertThat(Workspace.hasTopLevelStatements("println(1);")).isTrue();
    assertThat(Workspace.hasTopLevelStatements("int f() => 1;\nclass A {}")).isFalse();
    assertThat(Workspace.packageOf("package a.b;\nclass A {}")).isEqualTo("a.b");
  }
}
