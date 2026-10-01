import fs from "node:fs";

const source = fs.readFileSync(process.argv[2], "utf8");
const calls = [];
globalThis.KWebAppPathsBridge = Object.freeze({
  createClient() {
    return {
      resolve(request, options) {
        calls.push({ request, options });
        return Promise.resolve({ kind: request.kind, path: `/fixture/${request.kind}`, source: "fixture" });
      },
    };
  },
});
globalThis.KWebApplicationStreamsBridge = Object.freeze({
  createClient() {
    return {
      openDownloadProgress(request, options) {
        let closed = false;
        const values = [{ downloadId: request.downloadId, done: false }, { downloadId: request.downloadId, done: true }];
        return {
          close() { closed = true; },
          async *[Symbol.asyncIterator]() {
            for (const value of values) {
              if (closed || options?.signal?.aborted) return;
              yield value;
            }
          },
        };
      },
    };
  },
});
globalThis.FilesBridge = Object.freeze({
  createClient() {
    return {
      openWorkspace(request, options) {
        return Promise.resolve({ handle: "workspace", kind: "directory", name: request.workspaceId, grants: request.grants });
      },
      openFile(request, options) {
        return Promise.resolve({ handle: "file", kind: "file", name: request.name, grants: ["read", "write"] });
      },
      writeFile(request, options) {
        return Promise.resolve({ written: request.bytes.length });
      },
      readFile(request, options) {
        return Promise.resolve({ bytes: [109, 105, 103, 114, 97, 116, 105, 111, 110], eof: true });
      },
      listDirectory(request, options) {
        return Promise.resolve({ entries: [{ name: "fixture.txt", kind: "file" }], truncated: false });
      },
      closeHandle(request, options) {
        return Promise.resolve({ closed: true });
      },
      openWatchDirectory(request, options) {
        let closed = false;
        return {
          close() { closed = true; },
          async *[Symbol.asyncIterator]() {
            if (!closed && !options?.signal?.aborted) yield { sequence: "1", kind: "created", name: "watch.txt" };
          },
        };
      },
    };
  },
});
globalThis.ClipboardBridge = Object.freeze({
  createClient() {
    return {
      read(request, options) {
        return Promise.resolve({ sequence: "7", available: [{ handle: "opaque-handle", format: request.formats[0], encoding: "utf8", sizeBytes: "4" }] });
      },
      readPayload(request, options) {
        return Promise.resolve({ bytes: [116, 101, 115, 116], eof: true });
      },
      write(request, options) {
        return Promise.resolve({ sequence: "8" });
      },
      clear(request, options) {
        return Promise.resolve({ sequence: "9" });
      },
      closePayload(request, options) {
        return Promise.resolve({ closed: true });
      },
    };
  },
});
globalThis.ShellBridge = Object.freeze({
  createClient() {
    return {
      openExternal(request, options) { return Promise.resolve({ action: "open-external", outcome: "handler-accepted", resourceKind: null }); },
      openResource(request, options) { return Promise.resolve({ action: "open-resource", outcome: "handler-accepted", resourceKind: "file" }); },
      revealResource(request, options) { return Promise.resolve({ action: "reveal-resource", outcome: "handler-accepted", resourceKind: "file" }); },
      trashResource(request, options) { return Promise.resolve({ action: "trash-resource", outcome: "moved-to-trash", resourceKind: "file" }); },
    };
  },
});

eval(source);
const home = await globalThis.desktop.getPath("home", { timeoutMs: 123 });
if (home !== "/fixture/home") throw new Error(`Unexpected home result: ${home}`);
if (calls.length !== 1 || calls[0].request.kind !== "home" || calls[0].options.timeoutMs !== 123) {
  throw new Error(`The generated preload did not preserve the typed bridge call: ${JSON.stringify(calls)}`);
}
let unsupported = false;
try {
  await globalThis.desktop.getPath("logs");
} catch (error) {
  unsupported = error?.code === "migration.path-name.unsupported";
}
if (!unsupported) throw new Error("An unpublished Electron path was not rejected.");
const stream = globalThis.desktop.progress({ downloadId: "fixture" });
const progress = [];
for await (const chunk of stream) progress.push(chunk);
if (progress.length !== 2 || progress[0].downloadId !== "fixture") throw new Error("The generated named stream adapter did not preserve AsyncIterable values.");
const workspace = await globalThis.desktop.openWorkspace({ workspaceId: "documents", grants: ["read", "write"] });
if (workspace.handle !== "workspace") throw new Error("The generated files adapter did not preserve workspace requests.");
const file = await globalThis.desktop.openFile({ parent: workspace.handle, name: "fixture.txt", mode: "read-write", createIfMissing: true });
await globalThis.desktop.writeFile({ handle: file.handle, offset: "0", bytes: [1, 2, 3] });
const fileResult = await globalThis.desktop.readFile({ handle: file.handle, offset: "0", length: 16 });
if (fileResult.bytes.length !== 9 || !fileResult.eof) throw new Error("The generated files adapter did not preserve bounded file results.");
const clipboardRead = await globalThis.desktop.readClipboard({ selection: "system", formats: ["text/plain"] });
const clipboardChunk = await globalThis.desktop.readClipboardPayload({ handle: clipboardRead.available[0].handle, offset: "0", length: 4 });
if (new TextDecoder().decode(new Uint8Array(clipboardChunk.bytes)) !== "test" || !clipboardChunk.eof) throw new Error("The generated clipboard adapter did not expose bounded payload reads.");
const clipboardClosed = await globalThis.desktop.closeClipboardPayload({ handle: clipboardRead.available[0].handle });
if (!clipboardClosed.closed) throw new Error("The generated clipboard adapter did not close payload handles.");
const shellExternal = await globalThis.desktop.openExternal({ uri: "https://example.invalid" });
if (shellExternal.outcome !== "handler-accepted") throw new Error("The generated shell external adapter did not preserve typed outcomes.");
const shellTrash = await globalThis.desktop.trashResource({ handle: file.handle });
if (shellTrash.outcome !== "moved-to-trash") throw new Error("The generated shell trash adapter did not preserve typed outcomes.");
const watch = globalThis.desktop.watchDirectory({ handle: workspace.handle });
const watchValues = [];
for await (const event of watch) watchValues.push(event);
if (watchValues.length !== 1 || watchValues[0].name !== "watch.txt") throw new Error("The generated files watch adapter did not preserve stream values.");
console.log("KWebShell generated Electron preload runtime passed.");
