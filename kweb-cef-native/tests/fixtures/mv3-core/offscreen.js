const expectedExtensionId = "dhhnhmffjehhodphofnkingncijnaona";
const expectedOrigin = `chrome-extension://${expectedExtensionId}`;
const expectedPage = "/offscreen.html";
const expectedReason = "DOM_PARSER";
const expectedParserMarker = "KWebShell offscreen parser";

const reportFailure = async (error) => {
  const message = error instanceof Error ? error.message : String(error);
  document.title = `KWEB_MV3_OFFSCREEN_DOCUMENT_FAIL|${message}`;
  await chrome.runtime.sendMessage({
    kind: "kwebshell-mv3-offscreen-failure",
    error: message
  });
};

const verify = async () => {
  if (location.origin !== expectedOrigin || location.pathname !== expectedPage) {
    throw new Error(`location:${location.href}`);
  }
  if (chrome.runtime.id !== expectedExtensionId) {
    throw new Error(`extension-id:${chrome.runtime.id}`);
  }
  if (
    typeof chrome.runtime.getURL !== "function" ||
    chrome.runtime.getURL("offscreen.html") !== location.href
  ) {
    throw new Error("runtime-url");
  }

  const source = `<main data-kwebshell-parser="${expectedParserMarker}">` +
    `${expectedParserMarker}</main>`;
  const parsed = new DOMParser().parseFromString(source, "text/html");
  const marker = parsed.querySelector("[data-kwebshell-parser]");
  if (
    marker?.dataset.kwebshellParser !== expectedParserMarker ||
    marker.textContent !== expectedParserMarker
  ) {
    throw new Error("dom-parser-result");
  }

  const response = await chrome.runtime.sendMessage({
    kind: "kwebshell-mv3-offscreen-ready",
    extensionId: chrome.runtime.id,
    origin: location.origin,
    page: location.pathname,
    reason: expectedReason,
    parser: marker.textContent,
    ready: 1
  });
  if (response?.accepted !== true) {
    throw new Error(`ready-rejected:${response?.error ?? "missing-response"}`);
  }
  document.title = "KWEB_MV3_OFFSCREEN_DOCUMENT_READY";
};

verify().catch((error) => {
  reportFailure(error).catch(() => {
    document.title = "KWEB_MV3_OFFSCREEN_DOCUMENT_FAIL|failure-report-rejected";
  });
});
