const workerInstance = crypto.randomUUID();

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.kind !== "kweb-lifecycle-probe") {
    return false;
  }
  (async () => {
    const stored = await chrome.storage.local.get({ probeCount: 0 });
    const probeCount = stored.probeCount + 1;
    await chrome.storage.local.set({ probeCount });
    sendResponse({
      extensionId: chrome.runtime.id,
      version: chrome.runtime.getManifest().version,
      workerInstance,
      probeCount,
      senderUrl: sender.url,
    });
  })().catch((error) => {
    sendResponse({ error: String(error) });
  });
  return true;
});
