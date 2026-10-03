# J# for Visual Studio Code

- Syntax highlighting for `.jsharp` files.
- Errors and warnings as you type: the same diagnostics as `jsharp check`.
- Hover: types and signatures.
- Go to definition, find references, highlighting of the symbol under the cursor.
- Rename (variables, functions, methods, fields, properties).
- Parameter hints while typing a call.
- Completion after `.` and of names in scope, and a document outline.
- **▶ Run** above a program's first statement (or its `main`), in the editor title bar, or with
  Ctrl+F5. It runs the program together with the library files next to it.

The J# compiler is bundled in the extension: the only requirement is **Java 25** (`JAVA_HOME`,
or `java` on the `PATH`).

## Install

From the repository root:

```
./gradlew installVscodeExtension     # builds and installs build/vscode/jsharp-<version>.vsix
```

or build the file and install it by hand (or share it):

```
./gradlew vscodeExtension
code --install-extension build/vscode/jsharp-0.1.0.vsix
```

No Node.js or npm is needed. To work on the extension itself, open `editors/vscode` in VS Code
and press F5. Without a bundled server it uses `jsharp` from the `PATH` or the
`jsharp.server.path` setting.

## Settings

- `jsharp.server.path`: a `jsharp` launcher to use instead of the bundled one, for example a
  local build at `<repo>/cli/build/install/jsharp/bin/jsharp`.
- `jsharp.classPath`: jars and class directories your code uses (for analysis and Run).

## How files are grouped

Each open file is analyzed with the other `.jsharp` (and `.java`) files under its source root:
its directory, or the directory its package path starts from. Files with top-level statements
are separate programs, so a folder of scripts works as well as a multi-file project.

## Limits

- Renaming types (classes, records, interfaces) is not supported yet: the extension says so
  instead of renaming part of the uses.
- References and rename cover the files analyzed together with the current file.

## Other editors

Any editor with LSP support can use the server: run `jsharp lsp` (optionally `-cp <path>`) over
stdio for files with the `.jsharp` extension. Logs go to stderr.
