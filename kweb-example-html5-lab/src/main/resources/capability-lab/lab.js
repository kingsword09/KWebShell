const state = document.getElementById("state");
const resultsElement = document.getElementById("results");
const phase = new URL(location.href).searchParams.get("phase");
const persistenceKey = "kwebshell.capability-lab.persistence.v1";
const persistenceValue = "kwebshell-capability-lab-v1";

const setState = (value) => { state.textContent = value; };
const text = (value) => typeof value === "string" ? value : JSON.stringify(value);
const unavailable = (reason, evidence) => ({
  status: "UNAVAILABLE",
  reason,
  evidence: Object.fromEntries(Object.entries(evidence ?? {}).map(([key, value]) => [key, text(value)]))
});
const pass = (evidence) => ({
  status: "PASS",
  reason: "",
  evidence: Object.fromEntries(Object.entries(evidence).map(([key, value]) => [key, text(value)]))
});

const withTimeout = (promise, milliseconds, label) => Promise.race([
  promise,
  new Promise((_, reject) => setTimeout(() => reject(new Error(`${label}-timeout`)), milliseconds))
]);

const readIndexedDb = () => new Promise((resolve, reject) => {
  const request = indexedDB.open("kwebshell-capability-lab-v1", 1);
  request.onupgradeneeded = () => request.result.createObjectStore("values");
  request.onerror = () => reject(request.error ?? new Error("indexeddb-open"));
  request.onsuccess = () => {
    const database = request.result;
    const read = database.transaction("values", "readonly").objectStore("values").get(persistenceKey);
    read.onerror = () => reject(read.error ?? new Error("indexeddb-read"));
    read.onsuccess = () => {
      const previous = read.result ?? null;
      const write = database.transaction("values", "readwrite").objectStore("values").put(persistenceValue, persistenceKey);
      write.onerror = () => reject(write.error ?? new Error("indexeddb-write"));
      write.onsuccess = () => { database.close(); resolve(previous); };
    };
  };
});

const serviceWorkerProbe = async () => {
  if (!("serviceWorker" in navigator)) return unavailable("navigator.serviceWorker-missing", { present: false });
  const registration = await navigator.serviceWorker.register("/service-worker.js", { scope: "/" });
  await navigator.serviceWorker.ready;
  const active = registration.active;
  if (!active) throw new Error("service-worker-active-missing");
  const response = await withTimeout(new Promise((resolve, reject) => {
    const channel = new MessageChannel();
    channel.port1.onmessage = (event) => resolve(event.data);
    active.postMessage({ kind: "kwebshell-capability-service-worker-v1" }, [channel.port2]);
    setTimeout(() => reject(new Error("service-worker-message-timeout")), 5000);
  }), 6000, "service-worker");
  if (response?.marker !== "kwebshell-capability-service-worker-v1") throw new Error("service-worker-marker");
  return pass({
    registration: "active",
    activeState: active.state,
    controller: navigator.serviceWorker.controller ? "present" : "absent",
    scope: registration.scope,
    marker: response.marker,
  });
};

const indexedDbProbe = async () => {
  const previous = await readIndexedDb();
  return pass({
    previousValue: previous === null ? "<absent>" : text(previous),
    currentValue: persistenceValue,
    database: "kwebshell-capability-lab-v1",
  });
};

const handlers = {
  "language.es-modules": async () => pass({ module: "lab.js", type: "module", syntax: "supported" }),
  "language.dynamic-import": async () => {
    const loaded = await import("/dynamic-module.js");
    if (loaded.topLevelValue !== "top-level-await-ok") throw new Error("dynamic-import-value");
    return pass({ module: "/dynamic-module.js", exported: loaded.topLevelValue });
  },
  "language.top-level-await": async () => {
    const loaded = await import("/dynamic-module.js");
    return loaded.topLevelValue === "top-level-await-ok"
      ? pass({ module: "/dynamic-module.js", value: loaded.topLevelValue })
      : (() => { throw new Error("top-level-await-value"); })();
  },
  "language.webassembly": async () => {
    const bytes = Uint8Array.from(atob("AGFzbQEAAAABBQFgAAF/AwIBAAcKAQZhbnN3ZXIAAAoGAQQAQSoL"), (character) => character.charCodeAt(0));
    const instance = await WebAssembly.instantiate(bytes);
    if (instance.instance.exports.answer() !== 42) throw new Error("wasm-answer");
    return pass({ export: "answer", value: "42", compile: "ok" });
  },
  "language.webassembly-simd": async () => {
    const simdBytes = Uint8Array.from(atob("AGFzbQEAAAABBQFgAAF7AwIBAAcFAQFmAAAKFgEUAP0MAAAAAAAAAAAAAAAAAAAAAAs="), (character) => character.charCodeAt(0));
    return WebAssembly.validate(simdBytes)
      ? pass({ validate: "true", instruction: "v128.const", bytes: String(simdBytes.byteLength) })
      : unavailable("webassembly-simd-unavailable", { validate: "false" });
  },
  "language.webassembly-threads": async () => {
    if (!crossOriginIsolated || typeof SharedArrayBuffer !== "function" || typeof Atomics !== "object") {
      return unavailable("webassembly-threads-prerequisites-unavailable", {
        crossOriginIsolated: String(crossOriginIsolated),
        SharedArrayBuffer: typeof SharedArrayBuffer,
        Atomics: typeof Atomics,
      });
    }
    const memory = new WebAssembly.Memory({ initial: 1, maximum: 2, shared: true });
    const values = new Int32Array(memory.buffer);
    Atomics.store(values, 0, 37);
    if (Atomics.load(values, 0) !== 37) throw new Error("shared-memory-atomics");
    return pass({ crossOriginIsolated: "true", sharedMemory: String(memory.buffer instanceof SharedArrayBuffer), atomicValue: "37" });
  },
  "dom.css-layout": async () => {
    const element = document.createElement("div");
    element.style.cssText = "position:absolute;left:0;top:0;width:137px;height:41px;padding:3px;";
    document.body.append(element);
    const rectangle = element.getBoundingClientRect();
    element.remove();
    if (rectangle.width < 137 || rectangle.height < 41) throw new Error("css-layout-size");
    return pass({ width: String(rectangle.width), height: String(rectangle.height), computed: getComputedStyle(document.body).display });
  },
  "dom.fonts": async () => {
    if (!document.fonts?.ready) throw new Error("font-face-set-missing");
    await document.fonts.ready;
    const systemAvailable = document.fonts.check("16px system-ui");
    if (!systemAvailable) throw new Error("system-font-unavailable");
    return pass({ fontFaceSet: "ready", status: document.fonts.status, systemUi: "available" });
  },
  "dom.observers": async () => {
    if (typeof ResizeObserver !== "function" || typeof IntersectionObserver !== "function") throw new Error("observer-constructor");
    const element = document.createElement("div");
    document.body.append(element);
    let resizeObserved = false;
    let intersectionObserved = false;
    const resize = new ResizeObserver(() => { resizeObserved = true; });
    const intersection = new IntersectionObserver(() => { intersectionObserved = true; });
    resize.observe(element); intersection.observe(element);
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    resize.disconnect(); intersection.disconnect(); element.remove();
    if (!resizeObserved || !intersectionObserved) throw new Error(`observer-callbacks:${resizeObserved}/${intersectionObserved}`);
    return pass({ resizeObserver: "present", intersectionObserver: "present", callbacks: `${resizeObserved}/${intersectionObserved}` });
  },
  "dom.shadow-dom": async () => {
    const host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "open" });
    shadow.innerHTML = "<span data-marker='shadow'>shadow-dom-v1</span>";
    document.body.append(host);
    const marker = shadow.querySelector("[data-marker='shadow']")?.textContent;
    host.remove();
    if (marker !== "shadow-dom-v1") throw new Error("shadow-marker");
    return pass({ mode: "open", marker });
  },
  "dom.input-events": async () => {
    const button = document.createElement("button");
    button.id = "capability-input-target";
    button.textContent = "Capability input target";
    button.style.cssText = "position:fixed;left:32px;top:24px;width:176px;height:48px;z-index:10000";
    document.body.append(button);
    document.title = `KWEB_CAPABILITY_LAB_${phase.toUpperCase()}_INPUT_READY`;
    try {
      const event = await withTimeout(new Promise((resolve) => {
        button.addEventListener("click", resolve, { once: true });
      }), 10000, "trusted-input");
      if (!event.isTrusted) throw new Error("input-event-not-trusted");
      return pass({ clickEvents: "1", trusted: "true", transport: "cdp-input-dispatch", coordinates: "120,48" });
    } finally { button.remove(); }
  },
  "graphics.canvas-2d": async () => {
    const canvas = document.createElement("canvas"); canvas.width = 2; canvas.height = 2;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("canvas-2d-context");
    context.fillStyle = "rgb(17,34,51)"; context.fillRect(0, 0, 1, 1);
    const pixel = Array.from(context.getImageData(0, 0, 1, 1).data).join(",");
    if (pixel !== "17,34,51,255") throw new Error(`canvas-pixel:${pixel}`);
    return pass({ context: "2d", pixel });
  },
  "graphics.webgl2": async () => {
    const canvas = document.createElement("canvas");
    const context = canvas.getContext("webgl2");
    if (!context) return unavailable("webgl2-context-unavailable", { present: "true", context: "null" });
    const debug = context.getExtension("WEBGL_debug_renderer_info");
    const extensions = context.getSupportedExtensions() ?? [];
    return pass({ context: "webgl2", vendor: debug ? String(context.getParameter(debug.UNMASKED_VENDOR_WEBGL)) : "redacted", renderer: debug ? String(context.getParameter(debug.UNMASKED_RENDERER_WEBGL)) : "redacted", extensionCount: String(extensions.length), extensions: extensions.sort().join(",") });
  },
  "graphics.webgpu": async () => {
    if (!("gpu" in navigator)) return unavailable("navigator.gpu-unavailable", { present: "false" });
    const adapter = await navigator.gpu.requestAdapter();
    if (!adapter) return unavailable("webgpu-adapter-unavailable", { navigatorGpu: "present", adapter: "null" });
    const device = await adapter.requestDevice();
    try {
      return pass({
        navigatorGpu: "present",
        adapter: "available",
        device: "created",
        featureCount: String(adapter.features.size),
        maxTextureDimension2D: String(adapter.limits.maxTextureDimension2D),
        vendor: adapter.info?.vendor || "unreported",
        architecture: adapter.info?.architecture || "unreported",
        deviceName: adapter.info?.device || "unreported",
      });
    } finally {
      device.destroy();
    }
  },
  "network.fetch-streams": async () => {
    const response = await fetch("/dynamic-module.js", { cache: "no-store" });
    if (!response.ok || !response.body?.getReader) throw new Error("fetch-stream");
    const reader = response.body.getReader(); let bytes = 0; let chunks = 0;
    while (true) { const next = await reader.read(); if (next.done) break; bytes += next.value.byteLength; chunks += 1; }
    if (bytes <= 0 || chunks <= 0) throw new Error("fetch-stream-empty");
    return pass({ status: String(response.status), bytes: String(bytes), chunks: String(chunks) });
  },
  "network.webcrypto": async () => {
    if (!crypto.subtle) throw new Error("crypto-subtle-missing");
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("kwebshell-capability-lab-v1"));
    const hex = Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, "0")).join("");
    if (hex.length !== 64) throw new Error("crypto-digest-length");
    return pass({ algorithm: "SHA-256", hex });
  },
  "network.websocket": async () => {
    const config = await fetch("/config.json", { cache: "no-store" }).then((response) => response.json());
    const reply = await withTimeout(new Promise((resolve, reject) => {
      const socket = new WebSocket(config.webSocketUrl);
      socket.onopen = () => socket.send("kwebshell-capability-lab-v1");
      socket.onmessage = (event) => { socket.close(); resolve(String(event.data)); };
      socket.onerror = () => reject(new Error("websocket-error"));
    }), 10000, "websocket");
    if (reply !== "echo:kwebshell-capability-lab-v1") throw new Error(`websocket-reply:${reply}`);
    return pass({ protocol: "websocket", reply });
  },
  "network.blob-url": async () => {
    const url = URL.createObjectURL(new Blob(["kwebshell-blob-v1"], { type: "text/plain" }));
    try {
      const value = await fetch(url).then((response) => response.text());
      if (value !== "kwebshell-blob-v1") throw new Error("blob-value");
      return pass({ objectUrl: "created", fetched: value });
    } finally { URL.revokeObjectURL(url); }
  },
  "network.cors": async () => {
    const config = await fetch("/config.json", { cache: "no-store" }).then((response) => response.json());
    const response = await fetch(config.crossOriginUrl, { mode: "cors", cache: "no-store" });
    if (!response.ok) throw new Error(`cors-status:${response.status}`);
    const body = await response.json();
    if (body.marker !== "kwebshell-cors-v1") throw new Error("cors-marker");
    return pass({ mode: "cors", sourceOrigin: location.origin, targetOrigin: new URL(config.crossOriginUrl).origin, marker: body.marker });
  },
  "network.compression-streams": async () => {
    if (typeof CompressionStream !== "function" || typeof DecompressionStream !== "function") {
      return unavailable("compression-streams-unavailable", { CompressionStream: typeof CompressionStream, DecompressionStream: typeof DecompressionStream });
    }
    const source = "kwebshell-compression-v1:".repeat(64);
    const compressed = new Blob([source]).stream().pipeThrough(new CompressionStream("gzip"));
    const restored = await new Response(compressed.pipeThrough(new DecompressionStream("gzip"))).text();
    if (restored !== source) throw new Error("compression-roundtrip");
    return pass({ format: "gzip", inputBytes: String(new TextEncoder().encode(source).byteLength), roundTrip: "exact" });
  },
  "storage.local-storage": async () => {
    const previous = localStorage.getItem(persistenceKey);
    localStorage.setItem(persistenceKey, persistenceValue);
    if (localStorage.getItem(persistenceKey) !== persistenceValue) throw new Error("local-storage-write");
    return pass({ previousValue: previous ?? "<absent>", currentValue: persistenceValue, quotaScope: location.origin });
  },
  "storage.cookies": async () => {
    const name = "kwebshell_capability_lab_v1";
    const previous = document.cookie.split("; ").some((entry) => entry.startsWith(`${name}=`));
    document.cookie = `${name}=present; Path=/; Max-Age=3600; SameSite=Strict`;
    const current = document.cookie.split("; ").some((entry) => entry === `${name}=present`);
    if (!current) throw new Error("cookie-write");
    return pass({ previousValue: previous ? "present" : "<absent>", currentValue: "present", sameSite: "Strict", persistent: "Max-Age=3600" });
  },
  "storage.indexed-db": indexedDbProbe,
  "storage.cache-storage": async () => {
    if (!("caches" in window)) return unavailable("cache-storage-unavailable", { present: "false" });
    const cache = await caches.open("kwebshell-capability-lab-v1");
    await cache.put("/capability-cache-v1", new Response("cache-v1"));
    const value = await (await cache.match("/capability-cache-v1")).text();
    if (value !== "cache-v1") throw new Error("cache-storage-value");
    return pass({ cache: "kwebshell-capability-lab-v1", value });
  },
  "workers.dedicated": async () => {
    const worker = new Worker("/worker.js");
    try {
      const response = await withTimeout(new Promise((resolve, reject) => {
        worker.onmessage = (event) => resolve(event.data);
        worker.onerror = () => reject(new Error("dedicated-worker-error"));
        worker.postMessage("kwebshell-capability-worker-v1");
      }), 5000, "dedicated-worker");
      if (response?.marker !== "kwebshell-capability-worker-v1") throw new Error("dedicated-worker-marker");
      return pass({ marker: response.marker, global: response.workerGlobal });
    } finally { worker.terminate(); }
  },
  "workers.shared": async () => {
    if (typeof SharedWorker !== "function") return unavailable("shared-worker-unavailable", { present: "false" });
    const worker = new SharedWorker("/shared-worker.js");
    worker.port.start();
    try {
      const response = await withTimeout(new Promise((resolve) => {
        worker.port.onmessage = (event) => resolve(event.data);
        worker.port.postMessage("kwebshell-capability-shared-worker-v1");
      }), 5000, "shared-worker");
      if (response?.marker !== "kwebshell-capability-shared-worker-v1") throw new Error("shared-worker-marker");
      return pass({ present: "true", marker: response.marker, global: response.workerGlobal });
    } finally { worker.port.close(); }
  },
  "workers.service-worker": serviceWorkerProbe,
  "media.web-codecs": async () => typeof VideoDecoder === "function"
    ? pass({ VideoDecoder: "present", decode: "api-discovered" })
    : unavailable("video-decoder-unavailable", { VideoDecoder: "absent" }),
  "media.media-source": async () => typeof MediaSource === "function"
    ? pass({ MediaSource: "present", sourceBuffer: "api-discovered" })
    : unavailable("media-source-unavailable", { MediaSource: "absent" }),
  "media.encrypted-media": async () => typeof navigator.requestMediaKeySystemAccess === "function"
    ? pass({ requestMediaKeySystemAccess: "present", keySystem: "not-requested-without-a-licensed-fixture" })
    : unavailable("encrypted-media-api-unavailable", { requestMediaKeySystemAccess: "absent" }),
  "media.audio-element": async () => {
    const sampleRate = 8000;
    const sampleCount = 800;
    const buffer = new ArrayBuffer(44 + sampleCount * 2);
    const view = new DataView(buffer);
    const ascii = (offset, value) => [...value].forEach((character, index) => view.setUint8(offset + index, character.charCodeAt(0)));
    ascii(0, "RIFF"); view.setUint32(4, 36 + sampleCount * 2, true); ascii(8, "WAVE"); ascii(12, "fmt ");
    view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    ascii(36, "data"); view.setUint32(40, sampleCount * 2, true);
    for (let index = 0; index < sampleCount; index += 1) {
      view.setInt16(44 + index * 2, Math.round(Math.sin(index / sampleRate * 440 * Math.PI * 2) * 6000), true);
    }
    const url = URL.createObjectURL(new Blob([buffer], { type: "audio/wav" }));
    const audio = document.createElement("audio");
    audio.preload = "metadata"; audio.src = url; document.body.append(audio);
    try {
      await withTimeout(new Promise((resolve, reject) => {
        audio.onloadedmetadata = resolve;
        audio.onerror = () => reject(new Error(`audio-element:${audio.error?.code ?? "unknown"}`));
        audio.load();
      }), 5000, "audio-metadata");
      if (!Number.isFinite(audio.duration) || audio.duration <= 0) throw new Error("audio-duration");
      return pass({ type: "audio/wav", duration: String(audio.duration), readyState: String(audio.readyState), playback: "not-started-without-user-gesture" });
    } finally { audio.remove(); URL.revokeObjectURL(url); }
  },
  "policy.permissions": async () => {
    if (!navigator.permissions?.query) return unavailable("permissions-api-unavailable", { present: "false" });
    try {
      const permission = await navigator.permissions.query({ name: "clipboard-read" });
      return pass({ source: "host-policy", name: "clipboard-read", state: permission.state });
    } catch (error) {
      return unavailable("host-policy-denied", { source: "host-policy", name: "clipboard-read", error: String(error) });
    }
  },
  "policy.clipboard": async () => {
    if (!navigator.clipboard) return unavailable("clipboard-api-unavailable", { source: "host-policy", present: "false" });
    let permission = "query-unavailable";
    try { permission = (await navigator.permissions.query({ name: "clipboard-read" })).state; } catch (error) { permission = `query-error:${String(error)}`; }
    return pass({ source: "host-policy", clipboard: "present", permission, operation: "not-read-without-user-consent" });
  },
  "policy.fullscreen": async () => document.fullscreenEnabled
    ? pass({ source: "host-policy", fullscreenEnabled: "true", operation: "not-entered-without-user-gesture" })
    : unavailable("fullscreen-disabled-by-host-policy", { source: "host-policy", fullscreenEnabled: "false" }),
  "policy.drag-drop": async () => {
    if (typeof DataTransfer !== "function" || typeof DragEvent !== "function") {
      return unavailable("drag-drop-api-unavailable", { source: "host-policy", DataTransfer: typeof DataTransfer, DragEvent: typeof DragEvent });
    }
    const transfer = new DataTransfer(); transfer.setData("text/plain", "kwebshell-drag-v1");
    const event = new DragEvent("drop", { dataTransfer: transfer });
    if (event.dataTransfer?.getData("text/plain") !== "kwebshell-drag-v1") throw new Error("drag-drop-payload");
    return pass({ source: "host-policy", constructors: "present", payload: "kwebshell-drag-v1", trustedInput: "not-injected" });
  },
  "policy.file-selection": async () => {
    const input = document.createElement("input"); input.type = "file";
    const supported = input.type === "file" && input.files !== null;
    if (!supported) return unavailable("file-input-unavailable", { source: "host-policy", inputType: input.type });
    return pass({ source: "host-policy", inputType: "file", showOpenFilePicker: String(typeof showOpenFilePicker === "function"), dialog: "not-opened-without-user-consent" });
  },
  "lifecycle.visibility": async () => pass({ visibilityState: document.visibilityState, hidden: String(document.hidden), pageLifecycle: "active" }),
};

const runProbe = async (definition) => {
  const startedAtMs = Date.now();
  try {
    const handler = handlers[definition.id];
    if (typeof handler !== "function") throw new Error(`handler-missing:${definition.id}`);
    const outcome = await handler();
    return {
      id: definition.id,
      category: definition.category,
      requirement: definition.requirement,
      source: definition.source,
      status: outcome.status,
      startedAtMs,
      endedAtMs: Date.now(),
      reason: outcome.reason,
      evidence: outcome.evidence,
    };
  } catch (error) {
    return {
      id: definition.id,
      category: definition.category,
      requirement: definition.requirement,
      source: definition.source,
      status: "FAIL",
      startedAtMs,
      endedAtMs: Date.now(),
      reason: error instanceof Error ? error.message : String(error),
      evidence: { thrown: error instanceof Error ? error.name : typeof error },
    };
  }
};

const run = async () => {
  if (phase !== "cold" && phase !== "warm") throw new Error(`invalid-phase:${phase}`);
  setState(`Loading ${phase} capability manifest...`);
  const manifestResponse = await fetch("/manifest.json", { cache: "no-store" });
  if (!manifestResponse.ok) throw new Error("manifest-fetch");
  const manifest = await manifestResponse.json();
  if (!Array.isArray(manifest) || manifest.length === 0) throw new Error("manifest-empty");
  setState(`Running ${manifest.length} probes (${phase})...`);
  const probes = [];
  for (const definition of manifest) probes.push(await runProbe(definition));
  const report = {
    schemaVersion: 1,
    phase,
    reportId: crypto.randomUUID(),
    origin: location.origin,
    generatedAtMs: Date.now(),
    userAgent: navigator.userAgent,
    secureContext: window.isSecureContext,
    probes,
  };
  resultsElement.textContent = JSON.stringify(report, null, 2);
  setState("Publishing locked capability report...");
  const response = await fetch("/report", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(report) });
  if (response.status !== 204) throw new Error(`report-publish:${response.status}`);
  setState(`Capability report published (${phase}).`);
  document.title = `KWEB_CAPABILITY_LAB_${phase.toUpperCase()}_PASS`;
};

run().catch((error) => {
  setState(`Capability lab failed: ${error instanceof Error ? error.message : String(error)}`);
  document.title = "KWEB_CAPABILITY_LAB_FAIL";
});
