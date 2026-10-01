import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("desktop", {
  getPath: (name: string) => ipcRenderer.invoke("app.getPath", name),
  openWorkspace: (request: unknown) => ipcRenderer.invoke("fs.openWorkspace", request),
  openFile: (request: unknown) => ipcRenderer.invoke("fs.openFile", request),
  writeFile: (request: unknown) => ipcRenderer.invoke("fs.writeFile", request),
  readFile: (request: unknown) => ipcRenderer.invoke("fs.readFile", request),
  listDirectory: (request: unknown) => ipcRenderer.invoke("fs.listDirectory", request),
  closeHandle: (request: unknown) => ipcRenderer.invoke("fs.closeHandle", request),
  readClipboard: (request: unknown) => ipcRenderer.invoke("clipboard.read", request),
  writeClipboard: (request: unknown) => ipcRenderer.invoke("clipboard.write", request),
  clearClipboard: (request: unknown) => ipcRenderer.invoke("clipboard.clear", request),
  readClipboardPayload: (request: unknown) => ipcRenderer.invoke("clipboard.readPayload", request),
  closeClipboardPayload: (request: unknown) => ipcRenderer.invoke("clipboard.closePayload", request),
  openExternal: (request: unknown) => ipcRenderer.invoke("shell.openExternal", request),
  openResource: (request: unknown) => ipcRenderer.invoke("shell.openResource", request),
  revealResource: (request: unknown) => ipcRenderer.invoke("shell.revealResource", request),
  trashResource: (request: unknown) => ipcRenderer.invoke("shell.trashResource", request),
});
