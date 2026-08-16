const instanceId = crypto.randomUUID();
const actionTitleFor = (messageCount) =>
  `KWebShell MV3 action count: ${messageCount}`;
const contextMenuId = "kwebshell-mv3-context-menu";
const contextMenuTitle = "KWebShell MV3 context item";
const controlledPagePattern = "https://kwebshell.test/*";

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
      if (message.mode === "context-menu") {
        await ensureContextMenu();
      }
      return {
        messageCount,
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
        senderUrl: sender.url
      });
    })
    .catch((error) => {
      sendResponse({ error: String(error) });
    });

  return true;
});
