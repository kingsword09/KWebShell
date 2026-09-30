import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("desktop", {
  getPath: (name: string) => ipcRenderer.invoke("app.getPath", name),
  openWorkspace: (request: unknown) => ipcRenderer.invoke("fs.openWorkspace", request),
  openFile: (request: unknown) => ipcRenderer.invoke("fs.openFile", request),
  writeFile: (request: unknown) => ipcRenderer.invoke("fs.writeFile", request),
  readFile: (request: unknown) => ipcRenderer.invoke("fs.readFile", request),
  listDirectory: (request: unknown) => ipcRenderer.invoke("fs.listDirectory", request),
  closeHandle: (request: unknown) => ipcRenderer.invoke("fs.closeHandle", request),
});
