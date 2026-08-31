import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("desktop", {
  getPath: (name: string) => ipcRenderer.invoke("app.getPath", name),
});
