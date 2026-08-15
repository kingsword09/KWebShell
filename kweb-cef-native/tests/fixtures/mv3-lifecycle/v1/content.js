document.documentElement.dataset.kwebLifecycleInjected = "true";

(async () => {
  try {
    const result = await chrome.runtime.sendMessage({ kind: "kweb-lifecycle-probe" });
    document.documentElement.dataset.kwebLifecycle = JSON.stringify(result);
  } catch (error) {
    document.documentElement.dataset.kwebLifecycleError = String(error);
  }
})();
