// VS Code client for the J# language server (`jsharp lsp`). Dependency-free: it speaks the
// Language Server Protocol (JSON-RPC over stdio) directly, so the extension needs no npm install.
"use strict";
const vscode = require("vscode");
const cp = require("child_process");
const fs = require("fs");
const path = require("path");

const LANGUAGE = "jsharp";
let server; // the running Server, or undefined
let output; // the "J#" output channel (server logs)
let diagnostics; // the DiagnosticCollection
let runTerminal;

// ---------------------------------------------------------------------- server process

/** The `jsharp` launcher: the setting, else the one bundled in the extension, else the PATH. */
function launcher(context) {
  const config = vscode.workspace.getConfiguration("jsharp");
  const configured = (config.get("server.path") || "").trim();
  if (configured !== "") {
    return configured;
  }
  const name = process.platform === "win32" ? "jsharp.bat" : "jsharp";
  const bundled = path.join(context.extensionPath, "server", "bin", name);
  if (fs.existsSync(bundled)) {
    if (process.platform !== "win32") {
      // Zip extraction may drop the executable bits.
      for (const f of [bundled, "runtime/bin/java", "runtime/lib/jspawnhelper"]) {
        const file = path.isAbsolute(f) ? f : path.join(context.extensionPath, "server", f);
        try {
          if (fs.existsSync(file)) {
            fs.chmodSync(file, 0o755);
          }
        } catch (e) {
          // read-only installation: try running it anyway
        }
      }
    }
    return bundled;
  }
  return "jsharp";
}

/** The Java runtime bundled with this (platform-specific) extension, if any. */
function bundledRuntime(context) {
  const home = path.join(context.extensionPath, "server", "runtime");
  return fs.existsSync(path.join(home, "release")) ? home : undefined;
}

/** True if `java` is on the PATH or JAVA_HOME is set. */
function javaInstalled() {
  if (process.env.JAVA_HOME) {
    return true;
  }
  const exe = process.platform === "win32" ? "java.exe" : "java";
  return (process.env.PATH || "")
    .split(path.delimiter)
    .some((dir) => dir && fs.existsSync(path.join(dir, exe)));
}

/**
 * Without an installed Java, terminals get the bundled runtime as JAVA_HOME, so `./gradlew` in a
 * new J# project works on a machine with nothing else installed.
 */
function offerBundledJava(context) {
  const env = context.environmentVariableCollection;
  env.clear();
  const runtime = bundledRuntime(context);
  if (runtime && !javaInstalled()) {
    env.description = "J#: the Java runtime bundled with the extension, for Gradle";
    env.replace("JAVA_HOME", runtime);
  }
}

function classPathArgs() {
  const classPath = vscode.workspace.getConfiguration("jsharp").get("classPath") || [];
  return classPath.length > 0 ? ["-cp", classPath.join(path.delimiter)] : [];
}

/** A JSON-RPC connection to `jsharp lsp`. */
class Server {
  constructor(command, args) {
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = Buffer.alloc(0);
    this.proc = cp.spawn(command, args, {
      stdio: ["pipe", "pipe", "pipe"],
      shell: process.platform === "win32",
    });
    this.proc.stdout.on("data", (chunk) => this.receive(chunk));
    this.proc.stderr.on("data", (chunk) => output.append(chunk.toString()));
    this.exited = new Promise((resolve) => {
      this.proc.on("error", (e) => resolve({ error: e }));
      this.proc.on("exit", (code) => resolve({ code }));
    });
  }

  send(message) {
    const body = Buffer.from(JSON.stringify(message), "utf8");
    this.proc.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
    this.proc.stdin.write(body);
  }

  request(method, params) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.send({ jsonrpc: "2.0", id, method, params });
    });
  }

  notify(method, params) {
    this.send({ jsonrpc: "2.0", method, params });
  }

  receive(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (true) {
      const headerEnd = this.buffer.indexOf("\r\n\r\n");
      if (headerEnd < 0) {
        return;
      }
      const header = this.buffer.slice(0, headerEnd).toString("ascii");
      const match = /Content-Length: *(\d+)/i.exec(header);
      const length = match ? parseInt(match[1], 10) : 0;
      if (this.buffer.length < headerEnd + 4 + length) {
        return;
      }
      const body = this.buffer.slice(headerEnd + 4, headerEnd + 4 + length).toString("utf8");
      this.buffer = this.buffer.slice(headerEnd + 4 + length);
      this.dispatch(JSON.parse(body));
    }
  }

  dispatch(message) {
    if (message.id !== undefined && message.method === undefined) {
      const waiter = this.pending.get(message.id);
      this.pending.delete(message.id);
      if (waiter) {
        if (message.error) {
          waiter.reject(new Error(message.error.message));
        } else {
          waiter.resolve(message.result);
        }
      }
    } else if (message.method === "textDocument/publishDiagnostics") {
      publishDiagnostics(message.params);
    } else if (message.id !== undefined) {
      this.send({ jsonrpc: "2.0", id: message.id, result: null }); // no server->client requests
    }
  }

  async stop() {
    try {
      await Promise.race([this.request("shutdown", null), delay(2000)]);
      this.notify("exit", null);
    } catch (e) {
      // already gone
    }
    setTimeout(() => this.proc.kill(), 1000);
  }
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function start(context) {
  const command = launcher(context);
  const s = new Server(command, ["lsp", ...classPathArgs()]);
  s.exited.then((result) => {
    if (server !== s) {
      return;
    }
    server = undefined;
    const why = result.error
      ? result.error.code === "ENOENT"
        ? `'${command}' was not found`
        : result.error.message
      : `it stopped (exit code ${result.code})`;
    vscode.window
      .showErrorMessage(
        bundledRuntime(context)
          ? `J#: the language server is not running: ${why}.`
          : `J#: the language server is not running: ${why}. It needs Java 25 (JAVA_HOME or java on the PATH).`,
        "Show Log",
        "Restart"
      )
      .then((choice) => {
        if (choice === "Show Log") {
          output.show();
        } else if (choice === "Restart") {
          vscode.commands.executeCommand("jsharp.restartServer");
        }
      });
  });
  server = s;
  try {
    await s.request("initialize", {
      processId: process.pid,
      rootUri: vscode.workspace.workspaceFolders?.[0]?.uri.toString() ?? null,
      capabilities: {},
    });
  } catch (e) {
    return; // reported by the exit handler
  }
  s.notify("initialized", {});
  for (const doc of vscode.workspace.textDocuments) {
    didOpen(doc);
  }
}

// ---------------------------------------------------------------------- documents

const changeTimers = new Map();

function isJSharp(doc) {
  return doc.languageId === LANGUAGE && doc.uri.scheme === "file";
}

function didOpen(doc) {
  if (server && isJSharp(doc)) {
    server.notify("textDocument/didOpen", {
      textDocument: { uri: doc.uri.toString(), languageId: LANGUAGE, version: doc.version, text: doc.getText() },
    });
  }
}

function didChange(event) {
  const doc = event.document;
  if (!server || !isJSharp(doc)) {
    return;
  }
  // Each change re-analyzes the file: wait for a short pause in typing.
  const key = doc.uri.toString();
  clearTimeout(changeTimers.get(key));
  changeTimers.set(key, setTimeout(() => flush(doc), 150));
}

/** Sends a pending change now (before a request needs the current text). */
function flush(doc) {
  const key = doc.uri.toString();
  if (!changeTimers.has(key)) {
    return;
  }
  clearTimeout(changeTimers.get(key));
  changeTimers.delete(key);
  if (server) {
    server.notify("textDocument/didChange", {
      textDocument: { uri: key, version: doc.version },
      contentChanges: [{ text: doc.getText() }],
    });
  }
}

function publishDiagnostics(params) {
  const uri = vscode.Uri.parse(params.uri);
  diagnostics.set(
    uri,
    params.diagnostics.map((d) => {
      const diag = new vscode.Diagnostic(toRange(d.range), d.message, toSeverity(d.severity));
      diag.code = d.code;
      diag.source = "J#";
      return diag;
    })
  );
}

function toSeverity(s) {
  switch (s) {
    case 1:
      return vscode.DiagnosticSeverity.Error;
    case 2:
      return vscode.DiagnosticSeverity.Warning;
    case 3:
      return vscode.DiagnosticSeverity.Information;
    default:
      return vscode.DiagnosticSeverity.Hint;
  }
}

// ---------------------------------------------------------------------- conversions

function toPosition(p) {
  return new vscode.Position(p.line, p.character);
}

function toRange(r) {
  return new vscode.Range(toPosition(r.start), toPosition(r.end));
}

function toLocation(l) {
  return new vscode.Location(vscode.Uri.parse(l.uri), toRange(l.range));
}

function at(doc, position) {
  return {
    textDocument: { uri: doc.uri.toString() },
    position: { line: position.line, character: position.character },
  };
}

/** Sends `method` for a position in `doc` (after any pending edit); null without a server. */
async function ask(method, doc, params) {
  if (!server) {
    return null;
  }
  flush(doc);
  return server.request(method, params);
}

// ---------------------------------------------------------------------- language features

// The server's semantic token legend (LanguageServer.TOKEN_TYPES / TOKEN_MODIFIERS).
const SEMANTIC_LEGEND = new vscode.SemanticTokensLegend(
  ["class", "interface", "enum", "typeParameter", "method", "function", "property", "variable", "parameter", "enumMember"],
  ["declaration", "static", "readonly"]
);

function registerProviders(context) {
  const selector = { language: LANGUAGE, scheme: "file" };
  const L = vscode.languages;
  context.subscriptions.push(
    L.registerHoverProvider(selector, {
      async provideHover(doc, position) {
        const h = await ask("textDocument/hover", doc, at(doc, position));
        if (!h) {
          return null;
        }
        return new vscode.Hover(new vscode.MarkdownString(h.contents.value), h.range && toRange(h.range));
      },
    }),
    L.registerDefinitionProvider(selector, {
      async provideDefinition(doc, position) {
        const d = await ask("textDocument/definition", doc, at(doc, position));
        return d ? toLocation(d) : null;
      },
    }),
    L.registerReferenceProvider(selector, {
      async provideReferences(doc, position, context) {
        const refs = await ask("textDocument/references", doc, {
          ...at(doc, position),
          context: { includeDeclaration: context.includeDeclaration },
        });
        return (refs || []).map(toLocation);
      },
    }),
    L.registerDocumentHighlightProvider(selector, {
      async provideDocumentHighlights(doc, position) {
        const hs = await ask("textDocument/documentHighlight", doc, at(doc, position));
        return (hs || []).map((h) => new vscode.DocumentHighlight(toRange(h.range)));
      },
    }),
    L.registerRenameProvider(selector, {
      async prepareRename(doc, position) {
        const r = await ask("textDocument/prepareRename", doc, at(doc, position));
        return r ? { range: toRange(r.range), placeholder: r.placeholder } : null;
      },
      async provideRenameEdits(doc, position, newName) {
        const result = await ask("textDocument/rename", doc, { ...at(doc, position), newName });
        const edit = new vscode.WorkspaceEdit();
        for (const [uri, edits] of Object.entries(result?.changes || {})) {
          for (const e of edits) {
            edit.replace(vscode.Uri.parse(uri), toRange(e.range), e.newText);
          }
        }
        return edit;
      },
    }),
    L.registerSignatureHelpProvider(
      selector,
      {
        async provideSignatureHelp(doc, position) {
          const s = await ask("textDocument/signatureHelp", doc, at(doc, position));
          if (!s || s.signatures.length === 0) {
            return null;
          }
          const help = new vscode.SignatureHelp();
          help.signatures = s.signatures.map((sig) => {
            const info = new vscode.SignatureInformation(sig.label);
            info.parameters = sig.parameters.map((p) => new vscode.ParameterInformation(p.label));
            return info;
          });
          help.activeSignature = s.activeSignature;
          help.activeParameter = s.activeParameter;
          return help;
        },
      },
      { triggerCharacters: ["(", ","], retriggerCharacters: [","] }
    ),
    L.registerCompletionItemProvider(
      selector,
      {
        async provideCompletionItems(doc, position) {
          const c = await ask("textDocument/completion", doc, at(doc, position));
          return (c?.items || []).map((i) => {
            const item = new vscode.CompletionItem(i.label, (i.kind || 1) - 1); // LSP kinds are 1-based
            item.detail = i.detail;
            item.sortText = i.sortText;
            return item;
          });
        },
      },
      "."
    ),
    L.registerDocumentSymbolProvider(selector, {
      async provideDocumentSymbols(doc) {
        const symbols = await ask("textDocument/documentSymbol", doc, {
          textDocument: { uri: doc.uri.toString() },
        });
        return (symbols || []).map(
          (s) =>
            new vscode.SymbolInformation(s.name, (s.kind || 1) - 1, s.containerName || "", toLocation(s.location))
        );
      },
    }),
    L.registerDocumentSemanticTokensProvider(
      selector,
      {
        async provideDocumentSemanticTokens(doc) {
          const t = await ask("textDocument/semanticTokens/full", doc, { textDocument: { uri: doc.uri.toString() } });
          return new vscode.SemanticTokens(Uint32Array.from(t?.data || []));
        },
      },
      SEMANTIC_LEGEND
    ),
    L.registerCodeLensProvider(selector, {
      async provideCodeLenses(doc) {
        const lenses = await ask("textDocument/codeLens", doc, { textDocument: { uri: doc.uri.toString() } });
        return (lenses || []).map(
          (l) =>
            new vscode.CodeLens(toRange(l.range), {
              title: l.command.title,
              command: l.command.command,
              arguments: [vscode.Uri.parse(l.command.arguments[0])],
            })
        );
      },
    })
  );
}

// ---------------------------------------------------------------------- run

function quote(s) {
  if (process.platform === "win32") {
    return `"${s}"`;
  }
  return `'${s.replace(/'/g, "'\\''")}'`;
}

/** Runs the program in `uri` (or the active editor) in a terminal, with the files it uses. */
async function run(context, uri) {
  const target = uri || vscode.window.activeTextEditor?.document.uri;
  if (!target) {
    return;
  }
  const doc = await vscode.workspace.openTextDocument(target);
  await vscode.workspace.saveAll(false);
  let files = [target.fsPath];
  if (server) {
    try {
      files = (await server.request("jsharp/programFiles", { textDocument: { uri: doc.uri.toString() } })) || files;
    } catch (e) {
      // run the file alone
    }
  }
  if (!runTerminal || runTerminal.exitStatus !== undefined) {
    runTerminal = vscode.window.createTerminal("J# Run");
  }
  runTerminal.show(true);
  const command = [quote(launcher(context)), "run", ...classPathArgs(), ...files.map(quote), "--"].join(" ");
  runTerminal.sendText(process.platform === "win32" ? `& ${command}` : command);
}

// ---------------------------------------------------------------------- new project

const TEMPLATES = [
  { id: "app", label: "Application", detail: "A program, built with Gradle (./gradlew run)" },
  { id: "paper", label: "Paper plugin", detail: "A Minecraft server plugin (./gradlew runServer)" },
  { id: "library", label: "Library", detail: "Code for J# and Java projects, built with Gradle" },
  { id: "script", label: "Script", detail: "A single file, no build tool (the Run button)" },
];

/** `jsharp new`, as a short series of prompts; then opens the new project (D098). */
async function newProject(context) {
  const template = await vscode.window.showQuickPick(TEMPLATES, {
    title: "New J# Project (1/3): template",
  });
  if (!template) {
    return;
  }
  const name = await vscode.window.showInputBox({
    title: "New J# Project (2/3): name",
    value: template.id === "paper" ? "MyPlugin" : "my-app",
    validateInput: (v) =>
      /^[A-Za-z0-9][A-Za-z0-9 ._-]*$/.test(v) ? undefined : "Letters, digits, '-', '_', '.' and spaces",
  });
  if (!name) {
    return;
  }
  let pkg = "";
  if (template.id !== "script") {
    const simple = name.toLowerCase().replace(/[^a-z0-9]/g, "") || "app";
    pkg = await vscode.window.showInputBox({
      title: "New J# Project (3/3): package",
      value: `com.example.${/^[0-9]/.test(simple) ? "app" + simple : simple}`,
      validateInput: (v) =>
        /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/.test(v) ? undefined : "A package name, like com.example.app",
    });
    if (!pkg) {
      return;
    }
  }
  const parent = await vscode.window.showOpenDialog({
    title: "Create the project in this folder",
    openLabel: "Create Here",
    canSelectFiles: false,
    canSelectFolders: true,
    canSelectMany: false,
  });
  if (!parent || parent.length === 0) {
    return;
  }
  const dir = path.join(parent[0].fsPath, name);
  const args = ["new", template.id, dir, "--name", name, ...(pkg ? ["--package", pkg] : [])];
  const result = await new Promise((resolve) => {
    const command = launcher(context);
    const win = process.platform === "win32";
    cp.execFile(
      win ? quote(command) : command,
      win ? args.map(quote) : args,
      { shell: win },
      (error, stdout, stderr) => resolve({ error, stdout, stderr })
    );
  });
  if (result.error) {
    const why = (result.stderr || result.error.message).trim().replace(/^jsharp: /, "");
    vscode.window.showErrorMessage(`J#: could not create the project: ${why}`);
    return;
  }
  const choice = await vscode.window.showInformationMessage(
    `Created ${template.label.toLowerCase()} '${name}'.`,
    "Open",
    "Open in New Window"
  );
  if (choice) {
    await vscode.commands.executeCommand("vscode.openFolder", vscode.Uri.file(dir), {
      forceNewWindow: choice === "Open in New Window",
    });
  }
}

// ---------------------------------------------------------------------- activation

async function activate(context) {
  output = vscode.window.createOutputChannel("J#");
  diagnostics = vscode.languages.createDiagnosticCollection("jsharp");
  context.subscriptions.push(
    output,
    diagnostics,
    vscode.workspace.onDidOpenTextDocument(didOpen),
    vscode.workspace.onDidChangeTextDocument(didChange),
    vscode.workspace.onDidSaveTextDocument((doc) => {
      if (server && isJSharp(doc)) {
        flush(doc);
        server.notify("textDocument/didSave", { textDocument: { uri: doc.uri.toString() } });
      }
    }),
    vscode.workspace.onDidCloseTextDocument((doc) => {
      if (server && isJSharp(doc)) {
        server.notify("textDocument/didClose", { textDocument: { uri: doc.uri.toString() } });
        diagnostics.delete(doc.uri);
      }
    }),
    vscode.commands.registerCommand("jsharp.run", (uri) => run(context, uri)),
    vscode.commands.registerCommand("jsharp.newProject", () => newProject(context)),
    vscode.commands.registerCommand("jsharp.restartServer", async () => {
      const old = server;
      server = undefined;
      diagnostics.clear();
      if (old) {
        await old.stop();
      }
      await start(context);
    }),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("jsharp")) {
        vscode.commands.executeCommand("jsharp.restartServer");
      }
    })
  );
  registerProviders(context);
  offerBundledJava(context);
  await start(context);
}

async function deactivate() {
  if (server) {
    await server.stop();
  }
}

module.exports = { activate, deactivate };
