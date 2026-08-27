const PHASE = new URLSearchParams(location.search).get("phase") || "standalone";
const SAMPLE_ID = new URLSearchParams(location.search).get("sampleId") || crypto.randomUUID();
const SCENARIO_ID = SAMPLE_ID;
const state = {
  config: null,
  route: location.hash.slice(2) || "proof",
  runStarted: 0,
  streamChunks: [],
  workerMessages: [],
  preexistingRecords: 0,
  storedRecords: 0,
  routeTransitions: 0,
  historyMoves: 0,
  rafTimes: [],
  lcp: null,
  cls: 0,
  longTasks: [],
  inp: null,
  maxDomRows: 0,
  audioSampleRate: null,
  decodedFontFamily: null,
  presentProbeFrame: 0,
  presentProbeRequest: 0,
  running: false,
  completed: false,
};
const $ = (selector) => document.querySelector(selector);
const metric = (name, value) => {
  const node = document.querySelector(`[data-metric="${name}"]`);
  if (node) node.textContent = typeof value === "number" ? formatMetric(name, value) : value;
};
const formatMetric = (name, value) => {
  if (name === "cls") return value.toFixed(4);
  if (["longtask", "socket", "worker", "records", "rows"].includes(name)) return String(Math.round(value));
  return `${value.toFixed(2)} ms`;
};
const proofMarkup = $("#proof-stage")?.innerHTML || "";
const readyPromise = loadConfig().catch((error) => {
  const message = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  window.__kwebBenchmarkFailure = message;
  document.title = `KWEBSHELL_BENCHMARK_${PHASE.toUpperCase()}_FAIL`;
  throw error;
});

function setStatus(text, mode = "") {
  $("#run-status").textContent = text;
  $(".press-header").classList.toggle("is-running", mode === "running");
  $(".press-header").classList.toggle("is-complete", mode === "complete");
  $("#ledger-state").textContent = mode === "complete" ? "RECORDED" : mode === "running" ? "RUNNING" : "UNRUN";
}

function renderVirtualRows(start = 0) {
  const list = $("#virtual-list");
  const count = 24;
  list.replaceChildren();
  for (let index = start; index < Math.min(start + count, 5000); index += 1) {
    const row = document.createElement("li");
    row.innerHTML = `<b>${String(index).padStart(4, "0")}</b><span>source fragment ${String(index).padStart(4, "0")} / synthetic record</span><code>sha:${((index * 2654435761) >>> 0).toString(16).padStart(8, "0")}</code><time>${(index % 60).toString().padStart(2, "0")}s</time>`;
    list.append(row);
  }
  state.maxDomRows = Math.max(state.maxDomRows, list.children.length);
  $("#row-window").textContent = `rows ${String(start).padStart(4, "0")}–${String(Math.min(start + count - 1, 4999)).padStart(4, "0")}`;
  metric("rows", state.maxDomRows);
}

function initVirtualization() {
  renderVirtualRows(0);
  $("#virtual-viewport").addEventListener("scroll", (event) => {
    const viewport = event.currentTarget;
    const start = Math.floor(viewport.scrollTop / 29);
    renderVirtualRows(start);
  }, { passive: true });
}

function installObservers() {
  try {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) state.longTasks.push(entry.duration);
    }).observe({ type: "longtask", buffered: true });
  } catch (error) { console.warn("longtask observer unavailable", error); }
  try {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) if (!entry.hadRecentInput) state.cls += entry.value;
    }).observe({ type: "layout-shift", buffered: true });
  } catch (error) { console.warn("layout-shift observer unavailable", error); }
  try {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) state.lcp = entry.startTime;
    }).observe({ type: "largest-contentful-paint", buffered: true });
  } catch (error) { console.warn("lcp observer unavailable", error); }
  try {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) if (entry.interactionId) state.inp = Math.max(state.inp || 0, entry.duration);
    }).observe({ type: "event", buffered: true, durationThreshold: 16 });
  } catch (error) { state.inp = null; }
}

async function loadConfig() {
  const response = await fetch(`/benchmark-config.json?sampleId=${encodeURIComponent(SAMPLE_ID)}`, { cache: "no-store" });
  if (!response.ok) throw new Error(`benchmark-config HTTP ${response.status}`);
  const config = await response.json();
  if (!config.reportUrl || !config.webSocketUrl || !config.origin) throw new Error("benchmark-config is incomplete");
  if (location.origin !== config.origin) throw new Error(`unexpected workload origin ${location.origin}`);
  state.config = config;
  $("#run-shortcut").textContent = navigator.platform.startsWith("Mac") ? "Cmd+Enter" : "Ctrl+Enter";
  $("#profile-state").textContent = config.profileName;
  document.title = `KWEBSHELL_BENCHMARK_${config.phase.toUpperCase()}_READY`;
}

function navigate(route, fromHistory = false) {
  if (!["proof", "history", "library", "inspect"].includes(route)) throw new Error(`unknown workload route ${route}`);
  const previous = state.route;
  state.route = route;
  if (!fromHistory) history.pushState({ route }, "", `#/${route}`);
  if (previous !== route) state.routeTransitions += 1;
  document.querySelectorAll("[data-route]").forEach((link) => {
    link.toggleAttribute("aria-current", link.dataset.route === route);
  });
  const stage = $("#proof-stage");
  stage.hidden = route !== "proof" && route !== "library";
  if (route === "proof" || route === "library") {
    if (stage.dataset.routeView !== "proof") {
      stage.innerHTML = proofMarkup;
      stage.dataset.routeView = "proof";
      initVirtualization();
    }
    $("#proof-title").textContent = route === "library" ? "A long source list should stay long without becoming a long DOM." : "Can a native Chromium surface hold an application-scale conversation?";
    $("#library-title").textContent = route === "library" ? "Source library / focused" : "Source library";
  } else {
    stage.hidden = false;
    stage.replaceChildren($(route === "history" ? "#history-view" : "#inspect-view").content.cloneNode(true));
    stage.dataset.routeView = route;
  }
}

function installRoutes() {
  document.querySelectorAll("[data-route]").forEach((link) => link.addEventListener("click", (event) => {
    event.preventDefault();
    navigate(link.dataset.route);
  }));
  window.addEventListener("popstate", (event) => {
    state.historyMoves += 1;
    navigate(event.state?.route || location.hash.slice(2) || "proof", true);
  });
  window.addEventListener("hashchange", () => {
    const route = location.hash.slice(2) || "proof";
    if (route !== state.route) navigate(route, true);
  });
}

async function exerciseHistory() {
  navigate("history");
  const waitForRoute = (expectedRoute) => new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      window.removeEventListener("popstate", onPop);
      reject(new Error(`History API did not reach ${expectedRoute}`));
    }, 5000);
    const onPop = (event) => {
      const route = event.state?.route || "proof";
      if (route !== expectedRoute) return;
      clearTimeout(timeout);
      window.removeEventListener("popstate", onPop);
      resolve(route);
    };
    window.addEventListener("popstate", onPop);
  });
  const back = waitForRoute("proof");
  history.back();
  await back;
  const forward = waitForRoute("history");
  history.forward();
  await forward;
  navigate("proof");
  await new Promise((resolve) => setTimeout(resolve, 0));
}

async function streamProof() {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(state.config.webSocketUrl);
    $("#socket-state").textContent = "opening";
    socket.addEventListener("open", () => { $("#socket-state").textContent = "open"; });
    socket.addEventListener("message", (event) => {
      const chunk = String(event.data);
      state.streamChunks.push(chunk);
      renderStreamedMarkdown(state.streamChunks.join(""));
      $("#response-state").className = "response-state running";
      $("#response-state").innerHTML = `<i aria-hidden="true"></i>${state.streamChunks.length} chunks received`;
      metric("socket", state.streamChunks.length);
    });
    socket.addEventListener("close", () => { $("#socket-state").textContent = "closed"; resolve(); });
    socket.addEventListener("error", () => reject(new Error("workload WebSocket failed")));
  });
}

function renderStreamedMarkdown(value) {
  const sentences = value.trim().split(/(?<=\.)\s+/).filter(Boolean);
  $("#response-copy").innerHTML = sentences.map((sentence, index) => {
    const marked = index === 0
      ? `<strong>${escapeHtml(sentence)}</strong>`
      : index === 1
        ? `<em>${escapeHtml(sentence)}</em>`
        : `${escapeHtml(sentence)} <code>raw.json</code>`;
    return `<p>${marked}</p>`;
  }).join("");
}

function runWorker() {
  return new Promise((resolve, reject) => {
    const worker = new Worker("/worker.js");
    $("#worker-state").textContent = "active";
    worker.onmessage = (event) => { state.workerMessages.push(event.data); metric("worker", state.workerMessages.length); $("#worker-state").textContent = "complete"; worker.terminate(); resolve(event.data); };
    worker.onerror = (event) => { worker.terminate(); reject(new Error(event.message || "workload Worker failed")); };
    worker.postMessage({ values: Array.from({ length: 4096 }, (_, index) => index & 255), rounds: 12 });
  });
}

async function persistRecords() {
  const database = await new Promise((resolve, reject) => {
    const request = indexedDB.open("kwebshell-benchmark", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("proofs", { keyPath: "id" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("IndexedDB open failed"));
  });
  state.preexistingRecords = await new Promise((resolve, reject) => {
    const transaction = database.transaction("proofs", "readonly");
    const request = transaction.objectStore("proofs").count();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("IndexedDB preexisting count failed"));
  });
  if (PHASE === "cold" && state.preexistingRecords !== 0) throw new Error(`Cold Profile contained ${state.preexistingRecords} IndexedDB records`);
  if (PHASE === "warm" && state.preexistingRecords !== 160) throw new Error(`Warm Profile expected 160 persisted IndexedDB records, got ${state.preexistingRecords}`);
  const records = Array.from({ length: 160 }, (_, id) => ({ id, text: `synthetic proof record ${id}`, created: Date.now() }));
  await new Promise((resolve, reject) => {
    const transaction = database.transaction("proofs", "readwrite");
    const store = transaction.objectStore("proofs");
    records.forEach((record) => store.put(record));
    transaction.oncomplete = resolve;
    transaction.onerror = () => reject(transaction.error || new Error("IndexedDB write failed"));
  });
  state.storedRecords = await new Promise((resolve, reject) => {
    const transaction = database.transaction("proofs", "readonly");
    const request = transaction.objectStore("proofs").count();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("IndexedDB count failed"));
  });
  database.close();
  $("#profile-state").textContent = `${state.storedRecords} records`;
  metric("records", state.storedRecords);
}

async function decodeAssets() {
  const imageStarted = performance.now();
  const image = $("#proof-image");
  await image.decode();
  const imageMs = performance.now() - imageStarted;
  const fontStarted = performance.now();
  await document.fonts.load('16px "Proof Sans"');
  if (!document.fonts.check('16px "Proof Sans"')) throw new Error("Locked Proof Sans font did not become available after loading");
  state.decodedFontFamily = getComputedStyle(document.body).fontFamily;
  const fontMs = performance.now() - fontStarted;
  const audioStarted = performance.now();
  const context = new (window.AudioContext || window.webkitAudioContext)();
  const sampleRate = 8000;
  const sampleCount = 800;
  const audioBuffer = new ArrayBuffer(44 + sampleCount);
  const audioView = new DataView(audioBuffer);
  const writeAscii = (offset, value) => [...value].forEach((character, index) => audioView.setUint8(offset + index, character.charCodeAt(0)));
  writeAscii(0, "RIFF"); audioView.setUint32(4, 36 + sampleCount, true); writeAscii(8, "WAVE");
  writeAscii(12, "fmt "); audioView.setUint32(16, 16, true); audioView.setUint16(20, 1, true); audioView.setUint16(22, 1, true);
  audioView.setUint32(24, sampleRate, true); audioView.setUint32(28, sampleRate, true); audioView.setUint16(32, 1, true); audioView.setUint16(34, 8, true);
  writeAscii(36, "data"); audioView.setUint32(40, sampleCount, true);
  for (let index = 0; index < sampleCount; index += 1) audioView.setUint8(44 + index, 128 + Math.round(Math.sin(index / 10) * 48));
  const decoded = await context.decodeAudioData(audioBuffer);
  if (!decoded || decoded.length <= 0 || decoded.numberOfChannels !== 1 || decoded.sampleRate <= 0) throw new Error("Audio decode returned an invalid AudioBuffer");
  state.audioSampleRate = decoded.sampleRate;
  await context.close();
  return { imageMs, fontMs, audioMs: performance.now() - audioStarted };
}

function gpuEvidence() {
  const canvas = document.createElement("canvas");
  const gl = canvas.getContext("webgl2");
  if (!gl) return { gpuVendor: "unavailable:webgl2-context", gpuRenderer: "unavailable:webgl2-context" };
  const debug = gl.getExtension("WEBGL_debug_renderer_info");
  if (!debug) return { gpuVendor: "unavailable:debug-renderer-info", gpuRenderer: "unavailable:debug-renderer-info" };
  return {
    gpuVendor: String(gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) || "unavailable:vendor-empty"),
    gpuRenderer: String(gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) || "unavailable:renderer-empty"),
  };
}

function collectEvidence() {
  const gpu = gpuEvidence();
  return {
    ...gpu,
    webSocketProtocol: "RFC 6455",
    workerType: "DedicatedWorker",
    indexedDbDatabase: "kwebshell-benchmark",
    routeFinal: location.hash || "#/proof",
    fontFamily: state.decodedFontFamily || getComputedStyle(document.body).fontFamily,
    imageNaturalSize: `${$("#proof-image").naturalWidth}x${$("#proof-image").naturalHeight}`,
    audioSampleRate: state.audioSampleRate == null ? "unavailable:audio-decode" : String(state.audioSampleRate),
    phasePersistence: `${PHASE}:${state.preexistingRecords}->${state.storedRecords}`,
  };
}

function sampleFrames(durationMs = 180) {
  state.rafTimes = [];
  return new Promise((resolve) => {
    const started = performance.now();
    const tick = (now) => {
      state.rafTimes.push(now);
      if (now - started >= durationMs) resolve(); else requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}

function startPresentProbe() {
  const probe = $("#present-probe");
  if (!probe || state.presentProbeRequest) throw new Error("present probe is unavailable or already running");
  state.presentProbeFrame = 0;
  const colors = ["#008eaa", "#ba356f", "#d5a900", "#20231f"];
  probe.classList.add("sampling");
  const tick = () => {
    probe.style.backgroundColor = colors[state.presentProbeFrame % colors.length];
    state.presentProbeFrame += 1;
    state.presentProbeRequest = requestAnimationFrame(tick);
  };
  state.presentProbeRequest = requestAnimationFrame(tick);
  const bounds = probe.getBoundingClientRect();
  return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height };
}

function stopPresentProbe() {
  if (!state.presentProbeRequest) throw new Error("present probe is not running");
  cancelAnimationFrame(state.presentProbeRequest);
  state.presentProbeRequest = 0;
  $("#present-probe").classList.remove("sampling");
  return state.presentProbeFrame;
}

function collectPerformance(started, ended, assetTimes) {
  const navigation = performance.getEntriesByType("navigation")[0];
  const paints = performance.getEntriesByType("paint");
  const fcp = paints.find((entry) => entry.name === "first-contentful-paint")?.startTime;
  const resources = performance.getEntriesByType("resource").filter((entry) => entry.startTime >= started || entry.name.includes("proof-sheet"));
  const intervals = state.rafTimes.slice(1).map((time, index) => time - state.rafTimes[index]);
  if (!fcp || state.lcp == null || !navigation) throw new Error("required navigation paint evidence was not exposed");
  const metricMap = {
    "page.ready.ms": navigation.domContentLoadedEventEnd,
    "navigation.fcp.ms": fcp,
    "navigation.lcp.ms": state.lcp,
    "layout.cls.ratio": state.cls,
    "long-task.count": state.longTasks.length,
    "long-task.duration.ms": state.longTasks.reduce((total, value) => total + value, 0),
    "resource.count": resources.length,
    "resource.transfer.bytes": resources.reduce((total, entry) => total + (entry.transferSize || 0), 0),
    "resource.decoded.bytes": resources.reduce((total, entry) => total + (entry.decodedBodySize || 0), 0),
    "scenario.duration.ms": ended - started,
    "route.transition.count": state.routeTransitions,
    "history.back-forward.count": state.historyMoves,
    "markdown.node.count": $("#response-copy").querySelectorAll("p, strong, em, code, li").length,
    "code.token.count": $("#code-output").textContent.trim().split(/\s+/).length,
    "virtual-list.total-row.count": 5000,
    "virtual-list.max-dom-row.count": state.maxDomRows,
    "websocket.message.count": state.streamChunks.length,
    "websocket.payload.bytes": new TextEncoder().encode(state.streamChunks.join("")).byteLength,
    "worker.message.count": state.workerMessages.length,
    "indexeddb.preexisting.record.count": state.preexistingRecords,
    "indexeddb.record.count": state.storedRecords,
    "decode.image.ms": assetTimes.imageMs,
    "decode.font.ms": assetTimes.fontMs,
    "decode.audio.ms": assetTimes.audioMs,
    "raf.frame.count": state.rafTimes.length,
    "raf.interval.median.ms": percentile(intervals, .5),
    "raf.interval.p95.ms": percentile(intervals, .95),
    "raf.interval.worst.ms": Math.max(...intervals),
  };
  const optionalMetrics = state.inp == null ? {} : { "interaction.inp.ms": state.inp };
  const unavailableMetrics = state.inp == null ? { "interaction.inp.ms": "The Event Timing API exposed no interactionId during this deterministic run." } : {};
  for (const [name, value] of Object.entries(metricMap)) metric(name.split(".").at(-1), value);
  metric("fcp", metricMap["navigation.fcp.ms"]); metric("lcp", metricMap["navigation.lcp.ms"]); metric("cls", metricMap["layout.cls.ratio"]); metric("longtask", metricMap["long-task.count"]);
  return { metrics: metricMap, optionalMetrics, unavailableMetrics };
}

async function runProof() {
  if (!state.config) throw new Error("benchmark configuration is not loaded");
  if (state.running || state.completed) throw new Error("This benchmark sample is single-use and has already started.");
  state.running = true;
  const button = $("#run-proof");
  button.disabled = true;
  setStatus("Composing proof", "running");
  $("#response-state").className = "response-state running";
  $("#response-state").innerHTML = "<i aria-hidden=\"true\"></i>composing";
  state.runStarted = performance.now();
  try {
    await exerciseHistory();
    const framePromise = sampleFrames();
    const [, , , assetTimes] = await Promise.all([streamProof(), runWorker(), persistRecords(), decodeAssets()]);
    await framePromise;
    const ended = performance.now();
    const performanceData = collectPerformance(state.runStarted, ended, assetTimes);
    $("#elapsed-time").textContent = `${((ended - state.runStarted) / 1000).toFixed(3)} s`;
    $("#timeline-fill").style.transform = "scaleX(1)";
    const longTaskCount = performanceData.metrics["long-task.count"];
    $("#conformance-copy").textContent = `Recorded ${state.streamChunks.length} stream chunks, ${state.workerMessages.length} Worker reply, ${state.storedRecords} persisted records, and ${longTaskCount} long ${longTaskCount === 1 ? "task" : "tasks"}. Every required metric has runtime evidence.`;
    $("#response-state").className = "response-state done";
    $("#response-state").innerHTML = "<i aria-hidden=\"true\"></i>proof recorded";
    setStatus("Proof recorded", "complete");
    const observation = {
      schemaVersion: 1, scenarioId: SCENARIO_ID, phase: PHASE, startedAtMs: state.runStarted, endedAtMs: ended,
      metrics: performanceData.metrics, optionalMetrics: performanceData.optionalMetrics, unavailableMetrics: performanceData.unavailableMetrics,
      evidence: collectEvidence(),
    };
    const reportResponse = await fetch(state.config.reportUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(observation) });
    if (!reportResponse.ok) throw new Error(`benchmark report HTTP ${reportResponse.status}`);
    document.title = `KWEBSHELL_BENCHMARK_${PHASE.toUpperCase()}_PASS`;
    state.completed = true;
    button.querySelector("span").textContent = "Proof recorded";
    return observation;
  } finally {
    state.running = false;
    button.disabled = state.completed;
  }
}

window.kwebBenchmark = {
  ready: readyPromise.then(() => true),
  run: async () => {
    try {
      return await runProof();
    } catch (error) {
      const message = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
      document.title = `KWEBSHELL_BENCHMARK_${PHASE.toUpperCase()}_FAIL`;
      $("#conformance-copy").textContent = message;
      window.__kwebBenchmarkFailure = message;
      throw error;
    }
  },
  navigate: (route) => { navigate(route); return { route: state.route, transitions: state.routeTransitions }; },
  state: () => ({ route: state.route, transitions: state.routeTransitions, historyMoves: state.historyMoves }),
  evidence: () => collectEvidence(),
  startPresentProbe,
  stopPresentProbe,
};

function escapeHtml(value) { const node = document.createElement("span"); node.textContent = value; return node.innerHTML; }
function percentile(values, p) { if (!values.length) throw new Error("frame evidence is empty"); const sorted = [...values].sort((a, b) => a - b); return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))]; }

installObservers();
initVirtualization();
installRoutes();
window.kwebBenchmark.ready.then(() => {
  const run = () => runProof().catch((error) => { setStatus("Proof failed"); $("#conformance-copy").textContent = error.message; console.error(error); });
  $("#run-proof").addEventListener("click", run);
  window.addEventListener("keydown", (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
      event.preventDefault();
      run();
    }
  });
}).catch((error) => { setStatus("Configuration failed"); $("#conformance-copy").textContent = error.message; console.error(error); });
