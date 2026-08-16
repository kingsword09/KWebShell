const expectedExtensionId = "dhhnhmffjehhodphofnkingncijnaona";

if (
  location.origin !== `chrome-extension://${expectedExtensionId}` ||
  location.pathname !== "/devtools-panel.html" ||
  chrome.runtime.id !== expectedExtensionId
) {
  document.body.textContent = "KWebShell MV3 DevTools panel identity failure";
}
