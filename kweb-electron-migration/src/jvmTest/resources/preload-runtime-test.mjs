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
console.log("KWebShell generated Electron preload runtime passed.");
