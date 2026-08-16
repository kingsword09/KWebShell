const instanceId = crypto.randomUUID();
const actionTitleFor = (messageCount) =>
  `KWebShell MV3 action count: ${messageCount}`;

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
    .then(async (messageCount) => ({
      messageCount,
      ...(await updateActionState(messageCount))
    }))
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
