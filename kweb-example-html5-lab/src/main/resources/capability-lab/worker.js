self.onmessage = (event) => {
  if (event.data === "kwebshell-capability-worker-v1") {
    self.postMessage({ marker: "kwebshell-capability-worker-v1", workerGlobal: typeof self });
  } else {
    self.postMessage({ error: "unexpected-message" });
  }
};
