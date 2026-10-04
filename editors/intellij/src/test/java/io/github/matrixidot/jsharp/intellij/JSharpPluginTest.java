package io.github.matrixidot.jsharp.intellij;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The plugin in a headless IDE: file type, highlighting, editing, running, the language server. */
public class JSharpPluginTest extends BasePlatformTestCase {
  @Override
  protected void tearDown() throws Exception {
    try {
      // The light project is reused by the next test: stop the server before it is disposed.
      LspClientManager.getInstance(getProject()).stopClients(JSharpLspProvider.class);
      for (int i = 0;
          i < 50
              && !LspClientManager.getInstance(getProject())
                  .getClients(JSharpLspProvider.class)
                  .isEmpty();
          i++) {
        Thread.sleep(100);
        UIUtil.dispatchAllInvocationEvents();
      }
      // The platform's LSP hover support turns this on; the fixture checks settings are unchanged.
      CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = false;
    } finally {
      super.tearDown();
    }
  }

  public void testFileTypeAndHighlighting() {
    myFixture.configureByText(
        "demo.jsharp", "// hi\npublic record P(int x);\nvar s = $\"x = {x + 1}\";\n");
    assertSame(JSharpFileType.INSTANCE, myFixture.getFile().getFileType());
    JSharpLexer lexer = new JSharpLexer();
    String text = myFixture.getFile().getText();
    lexer.start(text, 0, text.length(), 0);
    List<String> tokens = new ArrayList<>();
    for (; lexer.getTokenType() != null; lexer.advance()) {
      if (lexer.getTokenType() != JSharpTokens.WHITE_SPACE) {
        tokens.add(
            lexer.getTokenType()
                + " "
                + text.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
      }
    }
    assertEquals(
        List.of(
            "LINE_COMMENT // hi",
            "KEYWORD public",
            "KEYWORD record",
            "IDENTIFIER P",
            "LPAREN (",
            "KEYWORD int",
            "IDENTIFIER x",
            "RPAREN )",
            "SEMI ;",
            "KEYWORD var",
            "IDENTIFIER s",
            "OPERATOR =",
            "STRING $\"x = ",
            "INTERPOLATION {",
            "IDENTIFIER x",
            "OPERATOR +",
            "NUMBER 1",
            "INTERPOLATION }",
            "STRING \"",
            "SEMI ;"),
        tokens);
  }

  public void testEnterBetweenBraces() {
    myFixture.configureByText("a.jsharp", "void f() {<caret>}");
    myFixture.type('\n');
    myFixture.checkResult("void f() {\n    <caret>\n}");
  }

  public void testEnterAfterOpenBraceAddsTheClosingOne() {
    myFixture.configureByText("a.jsharp", "class A {\n    void f() {<caret>\n}");
    myFixture.type('\n');
    myFixture.checkResult("class A {\n    void f() {\n        <caret>\n    }\n}");
  }

  public void testEnterKeepsIndentation() {
    myFixture.configureByText("a.jsharp", "void f() {\n    var x = 1;<caret>\n}");
    myFixture.type('\n');
    myFixture.checkResult("void f() {\n    var x = 1;\n    <caret>\n}");
  }

  public void testClosingBraceLinesUpWithItsOpening() {
    myFixture.configureByText("a.jsharp", "class A {\n    void f() {\n        <caret>");
    myFixture.type('}');
    myFixture.checkResult("class A {\n    void f() {\n    }<caret>");
  }

  public void testRunGutterAndConfiguration() {
    myFixture.configureByText(
        "main.jsharp", "import java.util.*;\n\nint twice(int x) => 2 * x;\n<caret>println(twice(2));\n");
    int entry = JSharpRunLineMarker.entryOffset(myFixture.getFile());
    assertEquals(myFixture.getCaretOffset(), entry);
    PsiElement first = myFixture.getFile().findElementAt(entry);
    assertNotNull(new JSharpRunLineMarker().getInfo(first));
    ConfigurationContext context =
        ConfigurationContext.createEmptyContextForLocation(
            new com.intellij.execution.PsiLocation<>(first));
    List<ConfigurationFromContext> configs =
        com.intellij.openapi.progress.ProgressManager.getInstance()
            .runProcess(
                () -> context.getConfigurationsFromContext(),
                new com.intellij.openapi.progress.EmptyProgressIndicator());
    assertNotNull(configs);
    JSharpRunConfiguration config =
        (JSharpRunConfiguration)
            configs.stream()
                .map(ConfigurationFromContext::getConfiguration)
                .filter(c -> c instanceof JSharpRunConfiguration)
                .findFirst()
                .orElseThrow();
    assertEquals("main.jsharp", config.getName());
    assertEquals(myFixture.getFile().getVirtualFile().getPath(), config.getFile());
  }

  public void testLibraryFilesHaveNoEntryPoint() {
    myFixture.configureByText("lib.jsharp", "public String greet(String n) => n;\n");
    assertEquals(-1, JSharpRunLineMarker.entryOffset(myFixture.getFile()));
  }

  /** What a run configuration executes: the bundled CLI on the IDE's Java, with library files. */
  public void testRunningAProgramWithItsLibraryFiles() throws Exception {
    Path dir = Files.createTempDirectory("jsharp-run");
    Path main = Files.writeString(dir.resolve("main.jsharp"), "println(greet(args[0]));\n");
    Files.writeString(dir.resolve("greet.jsharp"), "public String greet(String n) => $\"Hi, {n}!\";\n");
    Files.writeString(dir.resolve("other.jsharp"), "println(\"another program\");\n");
    List<String> files = JSharpPrograms.siblings(main);
    assertEquals(List.of(main.toString(), dir.resolve("greet.jsharp").toString()), files);
    List<String> args = new ArrayList<>(List.of("run"));
    args.addAll(files);
    args.addAll(List.of("--", "IDE"));
    var out =
        new com.intellij.execution.process.CapturingProcessHandler(JSharpPlugin.cli(args))
            .runProcess(60000);
    assertEquals(out.getStderr(), 0, out.getExitCode());
    assertEquals("Hi, IDE!\n", out.getStdout());
  }

  /** The server starts for .jsharp files and answers the IDE. */
  public void testLanguageServerAnswers() throws Exception {
    myFixture.configureByText("program.jsharp", "int x = 41;\nvar y = x + 1;\n");
    var file = myFixture.getFile().getVirtualFile();
    new JSharpLspProvider()
        .fileOpened(
            getProject(),
            file,
            d ->
                LspClientManager.getInstance(getProject())
                    .ensureClientStarted(JSharpLspProvider.class, d));
    LspClient client = null;
    for (int i = 0; i < 300 && client == null; i++) {
      Thread.sleep(100);
      myFixture.doHighlighting(); // the IDE drives LSP clients from highlighting
      UIUtil.dispatchAllInvocationEvents();
      client =
          LspClientManager.getInstance(getProject()).getClients(JSharpLspProvider.class).stream()
              .filter(c -> c.getState() == LspServerState.Running)
              .findFirst()
              .orElse(null);
    }
    assertNotNull("the J# language server did not start", client);
    LspClient running = client;
    var params =
        new org.eclipse.lsp4j.HoverParams(
            running.getDocumentIdentifier(file), new org.eclipse.lsp4j.Position(1, 4));
    org.eclipse.lsp4j.Hover hover = null;
    for (int i = 0; i < 50 && hover == null; i++) {
      UIUtil.dispatchAllInvocationEvents();
      hover = running.sendRequestSync(10000, ls -> ls.getTextDocumentService().hover(params));
      if (hover == null) {
        Thread.sleep(200); // the document may still be on its way to the server
      }
    }
    assertNotNull("no hover from the server", hover);
    assertTrue(
        hover.getContents().getRight().getValue(),
        hover.getContents().getRight().getValue().contains("var y: int"));
  }
}
