// VS Code client for the J# language server (`jsharp lsp`). Syntax highlighting works without
// this file; the language features need `npm install` (for vscode-languageclient).
const vscode = require("vscode");
const path = require("path");

let client;

function serverCommand() {
  const config = vscode.workspace.getConfiguration("jsharp");
  const configured = config.get("server.path");
  const command = configured && configured.trim() !== "" ? configured : "jsharp";
  const args = ["lsp"];
  const classPath = config.get("classPath") || [];
  if (classPath.length > 0) {
    args.push("-cp", classPath.join(path.delimiter));
  }
  return { command, args };
}

async function start(context) {
  let lc;
  try {
    lc = require("vscode-languageclient/node");
  } catch (e) {
    vscode.window.showWarningMessage(
      "J#: syntax highlighting only. Run `npm install` in the extension folder to enable the language server."
    );
    return;
  }
  const { command, args } = serverCommand();
  const serverOptions = { command, args, transport: lc.TransportKind.stdio };
  const clientOptions = {
    documentSelector: [{ scheme: "file", language: "jsharp" }],
    synchronize: {
      fileEvents: vscode.workspace.createFileSystemWatcher("**/*.jsharp"),
    },
  };
  client = new lc.LanguageClient("jsharp", "J# Language Server", serverOptions, clientOptions);
  try {
    await client.start();
  } catch (e) {
    vscode.window.showErrorMessage(
      `J#: could not start '${command} lsp' (${e.message}). Set "jsharp.server.path" to the jsharp launcher.`
    );
  }
}

async function activate(context) {
  context.subscriptions.push(
    vscode.commands.registerCommand("jsharp.restartServer", async () => {
      if (client) {
        await client.stop();
        client = undefined;
      }
      await start(context);
    })
  );
  await start(context);
}

async function deactivate() {
  if (client) {
    await client.stop();
  }
}

module.exports = { activate, deactivate };
