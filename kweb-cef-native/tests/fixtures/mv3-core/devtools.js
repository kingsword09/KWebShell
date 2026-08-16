const expectedExtensionId = "dhhnhmffjehhodphofnkingncijnaona";
const expectedOrigin = `chrome-extension://${expectedExtensionId}`;
const expectedPage = "/devtools.html";
const panelTitle = "KWebShell MV3 panel";
const panelPage = "devtools-panel.html";
const inspectedExpression = "globalThis.KWEB_DEVTOOLS_INSPECTED_VALUE";
const expectedInspectedValue = "kwebshell-devtools-inspected";

const evaluateInspectedPage = () => new Promise((resolve, reject) => {
  chrome.devtools.inspectedWindow.eval(
    inspectedExpression,
    (result, exceptionInfo) => {
      const runtimeError = chrome.runtime.lastError?.message;
      if (runtimeError) {
        reject(new Error(`inspected-eval:${runtimeError}`));
      } else if (exceptionInfo?.isException) {
        reject(new Error(`inspected-exception:${JSON.stringify(exceptionInfo)}`));
      } else {
        resolve(result);
      }
    }
  );
});

const createPanel = () => new Promise((resolve, reject) => {
  chrome.devtools.panels.create(panelTitle, "", panelPage, (panel) => {
    const runtimeError = chrome.runtime.lastError?.message;
    if (runtimeError) {
      reject(new Error(`panel-create:${runtimeError}`));
      return;
    }
    if (
      !panel ||
      typeof panel.onShown?.addListener !== "function" ||
      typeof panel.onHidden?.addListener !== "function"
    ) {
      reject(new Error("panel-callback-invalid"));
      return;
    }
    resolve();
  });
});

const verify = async () => {
  if (location.origin !== expectedOrigin || location.pathname !== expectedPage) {
    throw new Error(`location:${location.href}`);
  }
  if (chrome.runtime.id !== expectedExtensionId) {
    throw new Error(`extension-id:${chrome.runtime.id}`);
  }
  const manifest = chrome.runtime.getManifest();
  if (manifest.devtools_page !== "devtools.html") {
    throw new Error(`manifest-devtools-page:${manifest.devtools_page}`);
  }
  if (
    typeof chrome.devtools?.inspectedWindow?.eval !== "function" ||
    typeof chrome.devtools?.panels?.create !== "function"
  ) {
    throw new Error("devtools-api-missing");
  }

  const [inspectedValue] = await Promise.all([
    evaluateInspectedPage(),
    createPanel()
  ]);
  if (inspectedValue !== expectedInspectedValue) {
    throw new Error(`inspected-value:${JSON.stringify(inspectedValue)}`);
  }

  await chrome.storage.local.set({
    devtoolsConformance: {
      extensionId: chrome.runtime.id,
      origin: location.origin,
      page: location.pathname,
      panelTitle,
      panelPage,
      inspectedValue,
      evalCompleted: true,
      panelCreated: true
    }
  });
};

verify().catch((error) => {
  chrome.storage.local.set({
    devtoolsFailure: error instanceof Error ? error.message : String(error)
  });
});
