export async function readHomePath(): Promise<string> {
  return window.desktop.getPath("home");
}

export async function readDownloadsPath(): Promise<string> {
  return window.desktop.getPath("downloads");
}
