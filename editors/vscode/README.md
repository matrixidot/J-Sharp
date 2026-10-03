# J# for Visual Studio Code

Syntax highlighting for `.jsharp` files, plus, through the J# language server (`jsharp lsp`):

- errors and warnings as you type (the same diagnostics as `jsharp check`)
- hover: the type of an expression, or the signature of a method, property, field or class
- go to definition (locals, members, classes and top-level functions in your sources)
- document outline
- completion after `.` (members and extension methods) and of names in scope

## Install from this repository

1. Build the J# CLI: `./gradlew :cli:installDist` (from the repository root).
2. Install the extension's one dependency and package it (needs Node.js with npm):
   ```
   cd editors/vscode
   npm install
   npx @vscode/vsce package          # creates jsharp-0.1.0.vsix
   code --install-extension jsharp-0.1.0.vsix
   ```
   For development instead, open `editors/vscode` in VS Code and press F5.
3. Point the extension at the CLI in your settings, unless `jsharp` is on your `PATH`:
   ```json
   "jsharp.server.path": "/path/to/J-Sharp/cli/build/install/jsharp/bin/jsharp"
   ```
   Add jars your code uses with `"jsharp.classPath": ["lib/foo.jar"]`.

Without `npm install`, the extension still provides highlighting and tells you how to enable
the rest.

## How files are grouped

The server analyzes each open file together with the other `.jsharp` files under its source
root: its directory, or the directory its package path starts from. Files with top-level
statements (programs) are analyzed separately from each other, so a folder of scripts works as
well as a multi-file project.

## Other editors

Any editor with LSP support can use the server: run `jsharp lsp` (optionally `-cp <path>`) over
stdio for files with the `.jsharp` extension. Logs go to stderr.
