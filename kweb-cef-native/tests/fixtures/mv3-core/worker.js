const instanceId = crypto.randomUUID();

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.kind !== "kwebshell-mv3-core") {
    return false;
  }

  chrome.storage.local
    .get({ messageCount: 0 })
    .then(({ messageCount }) => {
      const nextMessageCount = messageCount + 1;
      return chrome.storage.local
        .set({ messageCount: nextMessageCount })
        .then(() => nextMessageCount);
    })
    .then((messageCount) => {
      sendResponse({
        extensionId: chrome.runtime.id,
        instanceId,
        manifestName: chrome.runtime.getManifest().name,
        messageCount,
        senderUrl: sender.url
      });
    })
    .catch((error) => {
      sendResponse({ error: String(error) });
    });

  return true;
});
