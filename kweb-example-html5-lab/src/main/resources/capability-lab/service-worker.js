self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("fetch", (event) => {
  // Keep the probe on the real network path while proving the worker handles
  // a fetch event. No alternate response is supplied.
  event.respondWith(fetch(event.request));
});
self.addEventListener("message", (event) => {
  if (event.data?.kind !== "kwebshell-capability-service-worker-v1") return;
  const reply = { marker: "kwebshell-capability-service-worker-v1", scope: self.registration.scope };
  if (event.ports?.[0]) event.ports[0].postMessage(reply);
});
