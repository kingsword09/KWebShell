const instanceId = crypto.randomUUID();
const actionTitleFor = (messageCount) =>
  `KWebShell MV3 action count: ${messageCount}`;
const contextMenuId = "kwebshell-mv3-context-menu";
const contextMenuTitle = "KWebShell MV3 context item";
const controlledPagePattern = "https://kwebshell.test/*";
const offscreenPage = "offscreen.html";
const offscreenPath = `/${offscreenPage}`;
const offscreenReason = "DOM_PARSER";
const offscreenJustification = "fixed conformance justification";
const offscreenParserMarker = "KWebShell offscreen parser";
const offscreenReadyTimeoutMs = 10000;
const expectedManifestName = "KWebShell MV3 core conformance";
const expectedManifestPermissions = ["storage", "contextMenus", "offscreen"];

let offscreenReadyWaiter;
let offscreenProbeInProgress = false;

const errorMessage = (error) =>
  error instanceof Error ? error.message : String(error);

const expectedOffscreenOrigin = () =>
  `chrome-extension://${chrome.runtime.id}`;

const expectedOffscreenRecord = () => ({
  extensionId: chrome.runtime.id,
  origin: expectedOffscreenOrigin(),
  page: offscreenPath,
  reason: offscreenReason,
  parser: offscreenParserMarker,
  before: false,
  during: true,
  closed: true,
  after: false,
  ready: 1
});

const validateOffscreenRecord = (record) => {
  const expected = expectedOffscreenRecord();
  if (!record || typeof record !== "object" || Array.isArray(record)) {
    throw new Error(`offscreen-record-type:${typeof record}`);
  }
  const expectedKeys = Object.keys(expected).sort();
  const actualKeys = Object.keys(record).sort();
  if (JSON.stringify(actualKeys) !== JSON.stringify(expectedKeys)) {
    throw new Error(`offscreen-record-keys:${JSON.stringify(actualKeys)}`);
  }
  for (const key of expectedKeys) {
    if (record[key] !== expected[key]) {
      throw new Error(
        `offscreen-record-${key}:${JSON.stringify(record[key])}`
      );
    }
  }
  return record;
};

const validateOffscreenSender = (sender) => {
  const expectedUrl = `${expectedOffscreenOrigin()}${offscreenPath}`;
  if (sender?.id !== chrome.runtime.id || sender.url !== expectedUrl) {
    throw new Error(
      `offscreen-sender:${sender?.id ?? "missing"}:${sender?.url ?? "missing"}`
    );
  }
};

const validateOffscreenReady = (message, sender) => {
  validateOffscreenSender(sender);
  const expected = {
    extensionId: chrome.runtime.id,
    origin: expectedOffscreenOrigin(),
    page: offscreenPath,
    reason: offscreenReason,
    parser: offscreenParserMarker,
    ready: 1
  };
  for (const [key, value] of Object.entries(expected)) {
    if (message[key] !== value) {
      throw new Error(
        `offscreen-ready-${key}:${JSON.stringify(message[key])}`
      );
    }
  }
  return message;
};

const beginOffscreenReadyWait = () => {
  if (offscreenReadyWaiter) {
    throw new Error("offscreen-ready-waiter-duplicate");
  }
  let resolvePromise;
  let rejectPromise;
  const promise = new Promise((resolve, reject) => {
    resolvePromise = resolve;
    rejectPromise = reject;
  });
  let timeoutId;
  const waiter = {
    resolve(value) {
      if (offscreenReadyWaiter !== waiter) {
        return;
      }
      clearTimeout(timeoutId);
      offscreenReadyWaiter = undefined;
      resolvePromise(value);
    },
    reject(error) {
      if (offscreenReadyWaiter !== waiter) {
        return;
      }
      clearTimeout(timeoutId);
      offscreenReadyWaiter = undefined;
      rejectPromise(error);
    }
  };
  timeoutId = setTimeout(() => {
    waiter.reject(new Error("offscreen-ready-timeout"));
  }, offscreenReadyTimeoutMs);
  offscreenReadyWaiter = waiter;
  return {
    promise,
    cancel(error) {
      waiter.reject(error);
    }
  };
};

const storeUnexpectedOffscreenFailure = (failure, sendResponse) => {
  chrome.storage.local.set({ offscreenFailure: failure }).then(
    () => sendResponse({ accepted: false, error: failure }),
    (storageError) => sendResponse({
      accepted: false,
      error: `${failure}|storage:${errorMessage(storageError)}`
    })
  );
  return true;
};

const runOffscreenConformance = async () => {
  if (offscreenProbeInProgress) {
    throw new Error("offscreen-probe-duplicate");
  }
  offscreenProbeInProgress = true;
  let documentCreated = false;
  let readyWait;
  try {
    const { offscreenConformance, offscreenFailure } =
      await chrome.storage.local.get({
        offscreenConformance: null,
        offscreenFailure: null
      });
    if (offscreenFailure !== null) {
      throw new Error(`offscreen-stored-failure:${offscreenFailure}`);
    }
    if (offscreenConformance !== null) {
      validateOffscreenRecord(offscreenConformance);
      return { source: "persisted" };
    }
    const manifest = chrome.runtime.getManifest();
    if (
      manifest.name !== expectedManifestName ||
      JSON.stringify(manifest.permissions) !==
        JSON.stringify(expectedManifestPermissions)
    ) {
      throw new Error("offscreen-manifest-identity");
    }
    if (
      typeof chrome.offscreen?.hasDocument !== "function" ||
      typeof chrome.offscreen.createDocument !== "function" ||
      typeof chrome.offscreen.closeDocument !== "function"
    ) {
      throw new Error("offscreen-api-missing");
    }

    const before = await chrome.offscreen.hasDocument();
    if (before !== false) {
      throw new Error(`offscreen-before:${before}`);
    }

    readyWait = beginOffscreenReadyWait();
    try {
      await chrome.offscreen.createDocument({
        url: offscreenPage,
        reasons: [offscreenReason],
        justification: offscreenJustification
      });
      documentCreated = true;
    } catch (error) {
      readyWait.cancel(error);
      await readyWait.promise.catch(() => undefined);
      throw new Error(`offscreen-create:${errorMessage(error)}`);
    }

    const ready = await readyWait.promise;
    const during = await chrome.offscreen.hasDocument();
    if (during !== true) {
      throw new Error(`offscreen-during:${during}`);
    }

    await chrome.offscreen.closeDocument();
    documentCreated = false;
    const after = await chrome.offscreen.hasDocument();
    if (after !== false) {
      throw new Error(`offscreen-after:${after}`);
    }

    const record = {
      extensionId: ready.extensionId,
      origin: ready.origin,
      page: ready.page,
      reason: ready.reason,
      parser: ready.parser,
      before,
      during,
      closed: true,
      after,
      ready: ready.ready
    };
    validateOffscreenRecord(record);
    await chrome.storage.local.set({ offscreenConformance: record });
    return { source: "created" };
  } catch (error) {
    readyWait?.cancel(error);
    if (documentCreated) {
      try {
        await chrome.offscreen.closeDocument();
      } catch (cleanupError) {
        throw new Error(
          `${errorMessage(error)}|cleanup:${errorMessage(cleanupError)}`
        );
      }
    }
    throw error;
  } finally {
    offscreenProbeInProgress = false;
  }
};

const registerContextMenu = async () => {
  await chrome.contextMenus.removeAll();
  await new Promise((resolve, reject) => {
    chrome.contextMenus.create({
      id: contextMenuId,
      title: contextMenuTitle,
      contexts: ["page"],
      documentUrlPatterns: [controlledPagePattern]
    }, () => {
      const error = chrome.runtime.lastError?.message;
      if (error) {
        reject(new Error(`context-menu-registration:${error}`));
      } else {
        resolve();
      }
    });
  });
};

let contextMenuRegistration;

const ensureContextMenu = () => {
  if (!contextMenuRegistration) {
    contextMenuRegistration = registerContextMenu();
  }
  return contextMenuRegistration;
};

const updateActionState = async (messageCount) => {
  const expectedBadgeText = String(messageCount);
  const expectedTitle = actionTitleFor(messageCount);
  await Promise.all([
    chrome.action.setBadgeText({ text: expectedBadgeText }),
    chrome.action.setTitle({ title: expectedTitle })
  ]);
  const [badgeText, title] = await Promise.all([
    chrome.action.getBadgeText({}),
    chrome.action.getTitle({})
  ]);
  if (badgeText !== expectedBadgeText || title !== expectedTitle) {
    throw new Error(`action-state:${badgeText}:${title}`);
  }
  return { badgeText, title };
};

chrome.contextMenus.onClicked.addListener((info) => {
  const recordClick = async () => {
    if (info.menuItemId !== contextMenuId) {
      throw new Error(`context-menu-id:${info.menuItemId}`);
    }
    if (info.pageUrl !== "https://kwebshell.test/mv3-core-self-test") {
      throw new Error(`context-menu-page:${info.pageUrl}`);
    }
    const { contextMenuClickCount } = await chrome.storage.local.get({
      contextMenuClickCount: 0
    });
    const clickCount = contextMenuClickCount + 1;
    await chrome.storage.local.set({
      contextMenuClick: {
        extensionId: chrome.runtime.id,
        menuItemId: contextMenuId,
        clickCount,
        pageUrl: info.pageUrl
      },
      contextMenuClickCount: clickCount
    });
  };
  recordClick().catch((error) => {
    chrome.storage.local.set({
      contextMenuFailure: String(error)
    });
  });
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.kind === "kwebshell-mv3-offscreen-ready") {
    if (!offscreenReadyWaiter) {
      return storeUnexpectedOffscreenFailure(
        "offscreen-ready-unexpected",
        sendResponse
      );
    }
    try {
      const ready = validateOffscreenReady(message, sender);
      offscreenReadyWaiter.resolve(ready);
      sendResponse({ accepted: true });
      return false;
    } catch (error) {
      const failure = errorMessage(error);
      offscreenReadyWaiter?.reject(error);
      sendResponse({ accepted: false, error: failure });
      return false;
    }
  }

  if (message?.kind === "kwebshell-mv3-offscreen-failure") {
    try {
      validateOffscreenSender(sender);
      const failure = `offscreen-document:${String(message.error)}`;
      if (offscreenReadyWaiter) {
        offscreenReadyWaiter.reject(new Error(failure));
        sendResponse({ accepted: true });
        return false;
      }
      return storeUnexpectedOffscreenFailure(failure, sendResponse);
    } catch (error) {
      return storeUnexpectedOffscreenFailure(errorMessage(error), sendResponse);
    }
  }

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
    .then(async (messageCount) => {
      let offscreenSource;
      if (message.mode === "context-menu") {
        await ensureContextMenu();
      }
      if (message.mode === "offscreen") {
        const offscreen = await runOffscreenConformance();
        offscreenSource = offscreen.source;
      }
      return {
        messageCount,
        offscreenSource,
        ...(await updateActionState(messageCount))
      };
    })
    .then((state) => {
      sendResponse({
        extensionId: chrome.runtime.id,
        instanceId,
        manifestName: chrome.runtime.getManifest().name,
        messageCount: state.messageCount,
        actionBadgeText: state.badgeText,
        actionTitle: state.title,
        senderUrl: sender.url,
        ...(state.offscreenSource
          ? { offscreenSource: state.offscreenSource }
          : {})
      });
    })
    .catch(async (error) => {
      let failure = errorMessage(error);
      if (message.mode === "offscreen") {
        try {
          await chrome.storage.local.set({ offscreenFailure: failure });
        } catch (storageError) {
          failure = `${failure}|storage:${errorMessage(storageError)}`;
        }
      }
      sendResponse({ error: failure });
    });

  return true;
});
