package io.github.matrixidot.jsharp.lsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  void javaSourcesOfTheModuleJoinTheUnit(@TempDir Path dir) throws java.io.IOException {
    Path js = dir.resolve("src/main/jsharp/app/use.jsharp");
    Path java = dir.resolve("src/main/java/app/Helper.java");
    Files.createDirectories(js.getParent());
    Files.createDirectories(java.getParent());
    Files.writeString(
        java,
        "package app;\npublic class Helper { public static int twice(int x) { return 2 * x; } }\n");
    Files.writeString(js, "package app;\npublic static int four() => Helper.twice(2);\n");
    Workspace ws = new Workspace(List.of());
    Workspace.Unit unit = ws.unit(js.toUri());
    assertThat(ws.unitMembers(js.toUri())).contains(java.toUri());
    assertThat(unit.comp.diagnostics().all()).isEmpty();
    // Go to definition on `Helper` leads into the Java file.
    var index = unit.comp.index();
    int at = Files.readString(js).indexOf("Helper");
    var ref = index.at(unit.file(js.toUri()), at).orElseThrow();
    var loc = index.declaration(ref.symbol()).orElseThrow();
    assertThat(loc.file().path()).endsWith("Helper.java");
    assertThat(Files.readString(java).substring(loc.span().start(), loc.span().end()))
        .isEqualTo("Helper");
  }

  /** A Gradle build records the libraries in build/jsharp/main.classpath (D095). */
  @Test
  void gradleProjectsUseTheRecordedClassPath(@TempDir Path dir) throws Exception {
    Path lib = dir.resolve("lib");
    Path libSrc = dir.resolve("libsrc/game/Server.java");
    Files.createDirectories(libSrc.getParent());
    Files.writeString(
        libSrc,
        "package game;\npublic class Server { public static String motd() { return \"hi\"; } }\n");
    int rc =
        javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", lib.toString(), libSrc.toString());
    assertThat(rc).isZero();
    Path js = dir.resolve("src/main/jsharp/app/plugin.jsharp");
    Files.createDirectories(js.getParent());
    Files.writeString(
        js, "package app;\nimport game.Server;\npublic String motd() => Server.motd();\n");
    Workspace ws = new Workspace(List.of());
    assertThat(ws.unit(js.toUri()).comp.diagnostics().hasErrors()).isTrue(); // not built yet

    Path listing = dir.resolve("build/jsharp/main.classpath");
    Files.createDirectories(listing.getParent());
    Files.writeString(listing, lib.toAbsolutePath() + "\n");
    ws.change(js.toUri(), Files.readString(js));
    assertThat(ws.unit(js.toUri()).comp.diagnostics().all()).isEmpty();
  }

  @Test
  void detectsTopLevelStatements() {
    assertThat(Workspace.hasTopLevelStatements("println(1);")).isTrue();
    assertThat(Workspace.hasTopLevelStatements("int f() => 1;\nclass A {}")).isFalse();
    assertThat(Workspace.packageOf("package a.b;\nclass A {}")).isEqualTo("a.b");
  }
}
