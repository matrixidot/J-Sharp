# J# for IntelliJ IDEA (and other JetBrains IDEs)

- A J# file type with an icon, and syntax highlighting from the J# compiler's own lexer
  (interpolation holes are highlighted as code).
- Errors as you type, completion, hover, go to definition, find usages, rename and parameter
  hints, from the J# language server through the IDE's built-in LSP client.
- Braces: Enter between `{` and `}` puts the `}` on its own line with the cursor on an indented
  line; Enter after an unmatched `{` adds the `}`; a `}` typed on its own line lines up with
  its `{`. Also bracket and quote pairing, and Ctrl+/ comments.
- **Run:** a green ▶ in the gutter at a program's first statement (or `main`), "Run
  'main.jsharp'" from the editor or project view, and J# run configurations (Run | Edit
  Configurations) with program arguments. The program's library files in the same folder are
  compiled with it.

The compiler is bundled and runs on the IDE's own Java runtime: nothing else to install.
Requires a JetBrains IDE 2026.2 or newer (IntelliJ IDEA, including the free edition, CLion, ...).

## Build and install

From the repository root:

```
./gradlew -p editors/intellij buildPlugin
```

Then in the IDE: Settings | Plugins | ⚙ | Install Plugin from Disk…, and pick
`editors/intellij/build/distributions/jsharp-intellij-0.1.0.zip`. Restart the IDE after
updating the plugin.

The build compiles against a local JetBrains IDE (default `/opt/clion`; pass
`-PidePath=/path/to/ide`). It is a separate Gradle build so the main build needs no IDE.

## Tests

`./gradlew -p editors/intellij test` runs the plugin in a headless IDE: file type and
highlighting, Enter and brace handling, the gutter Run icon and run configurations, running a
program with its library files, and the language server answering the IDE.
`./gradlew -p editors/intellij verifyPlugin` runs JetBrains' Plugin Verifier.
