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
console.log("KWebShell generated Electron preload runtime passed.");
