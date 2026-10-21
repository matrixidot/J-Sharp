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
    Path src = Path.of("/p/src").toAbsolutePath(); // with a drive letter on Windows
    assertThat(Workspace.sourceRoot(src.resolve("ledger/report/x.jsharp"), "ledger.report"))
        .isEqualTo(src);
    assertThat(Workspace.sourceRoot(src.resolve("report/x.jsharp"), "ledger.report"))
        .isEqualTo(src);
    assertThat(Workspace.sourceRoot(src.resolve("x.jsharp"), "ledger")).isEqualTo(src);
    assertThat(Workspace.sourceRoot(src.resolve("x.jsharp"), "")).isEqualTo(src);
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

  /**
   * In a Gradle source set the editor analyzes what the build compiles together: other files see
   * the script's functions, and a second script is an error, as in the build.
   */
  @Test
  void gradleSourceSetsAreOneProgram(@TempDir Path dir) throws java.io.IOException {
    Path pkg = dir.resolve("src/main/jsharp/app");
    Files.createDirectories(pkg);
    Path main = pkg.resolve("main.jsharp");
    Files.writeString(main, "package app;\nprintln(useScript());\npublic int fromScript() => 5;\n");
    Path other = pkg.resolve("other.jsharp");
    Files.writeString(
        other, "package app;\npublic int useScript() => fromScript() + Helper.twice(1);\n");
    Path java = dir.resolve("src/main/java/app/Helper.java");
    Files.createDirectories(java.getParent());
    Files.writeString(
        java,
        "package app;\npublic class Helper { public static int twice(int x) { return 2 * x; } }\n");
    Workspace ws = new Workspace(List.of());
    assertThat(ws.unit(other.toUri()).comp.diagnostics().all()).isEmpty();
    assertThat(ws.unit(main.toUri()).comp.diagnostics().all()).isEmpty();

    Path second = pkg.resolve("second.jsharp");
    Files.writeString(second, "package app;\nprintln(\"another program\");\n");
    ws.change(main.toUri(), Files.readString(main));
    assertThat(ws.unit(main.toUri()).comp.diagnostics().all())
        .anySatisfy(d -> assertThat(d.code().id()).isEqualTo("JS0411"));

    // Outside a Gradle source set, each script is its own program.
    Path loose = dir.resolve("scripts");
    Files.createDirectories(loose);
    Files.writeString(loose.resolve("a.jsharp"), "println(1);\n");
    Files.writeString(loose.resolve("b.jsharp"), "println(2);\n");
    assertThat(ws.unit(loose.resolve("a.jsharp").toUri()).comp.diagnostics().all()).isEmpty();
  }

  @Test
  void detectsTopLevelStatements() {
    assertThat(Workspace.hasTopLevelStatements("println(1);")).isTrue();
    assertThat(Workspace.hasTopLevelStatements("int f() => 1;\nclass A {}")).isFalse();
    assertThat(Workspace.packageOf("package a.b;\nclass A {}")).isEqualTo("a.b");
  }
}
