export async function readHomePath(): Promise<string> {
  return window.desktop.getPath("home");
}

export async function readDownloadsPath(): Promise<string> {
  return window.desktop.getPath("downloads");
}

export async function readScopedFile(): Promise<readonly number[]> {
  const grants = ["read", "write", "create", "enumerate", "watch", "metadata", "copy", "move"];
  const workspace = await window.desktop.openWorkspace({ workspaceId: "documents", grants }) as { handle: string };
  const file = await window.desktop.openFile({
    parent: workspace.handle,
    name: "fixture.txt",
    mode: "read-write",
    createIfMissing: true,
  }) as { handle: string };
  await window.desktop.writeFile({ handle: file.handle, offset: "0", bytes: [102, 105, 120, 116, 117, 114, 101] });
  const result = await window.desktop.readFile({ handle: file.handle, offset: "0", length: 64 });
  await window.desktop.listDirectory({ handle: workspace.handle, limit: 16 });
  await window.desktop.closeHandle({ handle: file.handle });
  return result.bytes;
}

export async function watchScopedDirectory(): Promise<string> {
  const stream = window.desktop.watchDirectory({ handle: "fixture-workspace" });
  for await (const event of stream) {
    stream.close();
    return String(event.name ?? "");
  }
  return "closed";
}

export async function readClipboardText(): Promise<unknown> {
  const result = await window.desktop.readClipboard({
    selection: "system",
    formats: ["text/plain"],
  });
  const payload = result.available.find((item) => item.format === "text/plain");
  if (!payload) return "";
  try {
    const chunk = await window.desktop.readClipboardPayload({
      handle: payload.handle,
      offset: "0",
      length: Number(payload.sizeBytes),
    });
    return new TextDecoder().decode(new Uint8Array(chunk.bytes));
  } finally {
    await window.desktop.closeClipboardPayload({ handle: payload.handle });
  }
}

export async function writeClipboardText(value: string): Promise<unknown> {
  return window.desktop.writeClipboard({
    selection: "system",
    items: [{ format: "text/plain", encoding: "utf8", bytes: Array.from(new TextEncoder().encode(value)) }],
  });
}

export async function clearClipboard(): Promise<unknown> {
  return window.desktop.clearClipboard({ selection: "system" });
}
