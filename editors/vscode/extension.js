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
      try {
        fs.chmodSync(bundled, 0o755); // zip extraction may drop the executable bit
      } catch (e) {
        // read-only installation: try running it anyway
      }
    }
    return bundled;
  }
  return "jsharp";
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
        `J#: the language server is not running: ${why}. It needs Java 25 (JAVA_HOME or java on the PATH).`,
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
  await start(context);
}

async function deactivate() {
  if (server) {
    await server.stop();
  }
}

module.exports = { activate, deactivate };
