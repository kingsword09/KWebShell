const expectedExtensionId = "dhhnhmffjehhodphofnkingncijnaona";
const expectedManifestName = "KWebShell MV3 core conformance";
const expectedPopupPath = "/popup.html";
const expectedDefaultTitle = "KWebShell MV3 action";
const expectedBadgeText = "2";
const expectedActionTitle = "KWebShell MV3 action count: 2";

const fail = (message) => {
  document.title = `KWEB_MV3_ACTION_POPUP_FAIL|${message}`;
};

const verify = async () => {
  if (location.origin !== `chrome-extension://${expectedExtensionId}`) {
    throw new Error(`origin:${location.origin}`);
  }
  if (location.pathname !== expectedPopupPath) {
    throw new Error(`path:${location.pathname}`);
  }
  if (chrome.runtime.id !== expectedExtensionId) {
    throw new Error(`extension-id:${chrome.runtime.id}`);
  }
  const manifest = chrome.runtime.getManifest();
  if (manifest.name !== expectedManifestName) {
    throw new Error("manifest-name");
  }
  if (manifest.action?.default_popup !== expectedPopupPath.slice(1)) {
    throw new Error("manifest-popup");
  }
  if (manifest.action.default_title !== expectedDefaultTitle) {
    throw new Error("manifest-title");
  }
  const [badgeText, actionTitle, { messageCount }] = await Promise.all([
    chrome.action.getBadgeText({}),
    chrome.action.getTitle({}),
    chrome.storage.local.get({ messageCount: 0 })
  ]);
  if (badgeText !== expectedBadgeText) {
    throw new Error(`badge:${badgeText}`);
  }
  if (actionTitle !== expectedActionTitle) {
    throw new Error(`title:${actionTitle}`);
  }
  if (messageCount !== 2) {
    throw new Error(`message-count:${messageCount}`);
  }
  document.title = `KWEB_MV3_ACTION_POPUP_PASS|id=${chrome.runtime.id}` +
    `|manifest=${encodeURIComponent(manifest.name)}` +
    `|popup=${encodeURIComponent(manifest.action.default_popup)}` +
    `|defaultTitle=${encodeURIComponent(manifest.action.default_title)}` +
    `|badge=${encodeURIComponent(badgeText)}` +
    `|title=${encodeURIComponent(actionTitle)}` +
    `|messageCount=${messageCount}|path=${location.pathname}`;
};

verify().catch((error) => {
  fail(error instanceof Error ? error.message : String(error));
});
