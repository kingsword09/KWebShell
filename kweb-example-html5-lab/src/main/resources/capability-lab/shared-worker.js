self.onconnect = (event) => {
  const port = event.ports[0];
  port.onmessage = (message) => {
    if (message.data === "kwebshell-capability-shared-worker-v1") {
      port.postMessage({
        marker: "kwebshell-capability-shared-worker-v1",
        workerGlobal: typeof self
      });
    } else {
      port.postMessage({ error: "unexpected-message" });
    }
  };
  port.start();
};
