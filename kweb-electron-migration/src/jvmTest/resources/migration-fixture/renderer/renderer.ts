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
