const sleep = (milliseconds) => new Promise((resolve) => {
  setTimeout(resolve, milliseconds);
});

const fail = (message) => {
  document.title = `KWEB_MV3_CORE_FAIL|${message}`;
};

const actionTitleFor = (messageCount) =>
  `KWebShell MV3 action count: ${messageCount}`;

const sendProbe = async () => {
  const response = await chrome.runtime.sendMessage({
    kind: "kwebshell-mv3-core"
  });
  if (!response || response.error) {
    throw new Error(response?.error ?? "missing-response");
  }
  return response;
};

const run = async () => {
  const configuration = document.documentElement.dataset;
  if (
    configuration.pageWorldRuntime !== "undefined" ||
    typeof globalThis.KWEB_PAGE_WORLD_MARKER !== "undefined"
  ) {
    throw new Error("content-script-isolated-world-missing");
  }
  const firstExpected = Number(configuration.firstCount);
  const secondExpected = Number(configuration.secondCount);
  const idleDelayMs = Number(configuration.idleDelayMs);
  const expectedExtensionId = configuration.extensionId;
  const first = await sendProbe();
  if (
    first.messageCount !== firstExpected ||
    first.extensionId !== expectedExtensionId ||
    first.manifestName !== "KWebShell MV3 core conformance" ||
    first.actionBadgeText !== String(firstExpected) ||
    first.actionTitle !== actionTitleFor(firstExpected) ||
    first.senderUrl !== location.href
  ) {
    throw new Error(`first-response:${JSON.stringify(first)}`);
  }

  await sleep(idleDelayMs);

  const second = await sendProbe();
  if (
    second.messageCount !== secondExpected ||
    second.extensionId !== expectedExtensionId ||
    second.manifestName !== "KWebShell MV3 core conformance" ||
    second.actionBadgeText !== String(secondExpected) ||
    second.actionTitle !== actionTitleFor(secondExpected) ||
    second.senderUrl !== location.href
  ) {
    throw new Error(`second-response:${JSON.stringify(second)}`);
  }
  if (first.instanceId === second.instanceId) {
    throw new Error("service-worker-was-not-suspended");
  }

  document.title = `KWEB_MV3_CORE_PASS|${configuration.mode}` +
    `|first=${first.messageCount}|second=${second.messageCount}` +
    `|suspended=true|isolated=true|id=${expectedExtensionId}`;
};

const start = () => {
  run().catch((error) => {
    fail(error instanceof Error ? error.message : String(error));
  });
};

if (document.readyState === "loading") {
  window.addEventListener("DOMContentLoaded", start, { once: true });
} else {
  start();
}
