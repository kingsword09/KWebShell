const expectedExtensionId = "dhhnhmffjehhodphofnkingncijnaona";
const expectedManifestName = "KWebShell MV3 core conformance";
const expectedPath = "/options.html";

const fail = (message) => {
  document.title = `KWEB_MV3_OPTIONS_FAIL|${message}`;
};

const verify = async () => {
  if (location.origin !== `chrome-extension://${expectedExtensionId}`) {
    throw new Error(`origin:${location.origin}`);
  }
  if (location.pathname !== expectedPath) {
    throw new Error(`path:${location.pathname}`);
  }
  if (chrome.runtime.id !== expectedExtensionId) {
    throw new Error(`extension-id:${chrome.runtime.id}`);
  }
  const manifest = chrome.runtime.getManifest();
  if (manifest.name !== expectedManifestName) {
    throw new Error("manifest-name");
  }
  const { messageCount } = await chrome.storage.local.get({ messageCount: 0 });
  if (messageCount !== 2) {
    throw new Error(`message-count:${messageCount}`);
  }
  document.title = `KWEB_MV3_OPTIONS_PASS|id=${chrome.runtime.id}` +
    `|manifest=${encodeURIComponent(manifest.name)}` +
    `|messageCount=${messageCount}|path=${location.pathname}`;
};

verify().catch((error) => {
  fail(error instanceof Error ? error.message : String(error));
});
