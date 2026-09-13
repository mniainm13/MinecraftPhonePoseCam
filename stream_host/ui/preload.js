const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  encStart: (opts) => ipcRenderer.invoke('enc-start', opts),
  encStop: () => ipcRenderer.invoke('enc-stop'),
  openEncDir: () => ipcRenderer.invoke('open-enc-dir'),
  netDiagnose: (opts) => ipcRenderer.invoke('net-diagnose', opts),
  listRes: () => ipcRenderer.invoke('list-res'),
  saveConfig: (cfg) => ipcRenderer.invoke('save-config', cfg),
  onEncLog: (cb) => ipcRenderer.on('enc-log', (_e, msg) => cb(msg)),
});
