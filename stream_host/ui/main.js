const { app, BrowserWindow, Tray, Menu, shell } = require('electron');
const path = require('path');
const { spawn } = require('child_process');

let win = null;
let tray = null;
let enc = null;

const ENC = path.join(__dirname, '..', 'native', 'phonecam_enc.exe');

function createWindow() {
  win = new BrowserWindow({
    width: 860,
    height: 720,
    minWidth: 560,
    minHeight: 480,
    backgroundColor: '#0B1220',
    title: 'PhoneCam Stream',
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.js'),
    },
  });
  win.loadFile(path.join(__dirname, 'index.html'));
  win.on('closed', () => { win = null; });
}

function startEnc(args = ['--port', '8091', '--fps', '30', '--bitrate', '20']) {
  stopEnc();
  try {
    enc = spawn(ENC, args, { cwd: path.dirname(ENC) });
    enc.stdout?.on('data', (d) => {
      if (win && !win.isDestroyed()) {
        win.webContents.send('enc-log', d.toString());
      }
    });
    enc.stderr?.on('data', (d) => {
      if (win && !win.isDestroyed()) {
        win.webContents.send('enc-log', d.toString());
      }
    });
    enc.on('exit', () => { enc = null; });
  } catch (e) {
    console.error('spawn enc', e);
  }
}

function stopEnc() {
  if (enc) {
    try { enc.kill(); } catch (_) {}
    enc = null;
  }
}

function createTray() {
  // 16x16 blue square as tray icon (no asset dependency)
  const icon = nativeImageBlue();
  tray = new Tray(icon);
  tray.setToolTip('PhoneCam Stream');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '打开面板', click: () => { if (win) win.show(); else createWindow(); } },
    { label: '启动推流', click: () => startEnc() },
    { label: '停止推流', click: () => stopEnc() },
    { type: 'separator' },
    { label: '退出', click: () => { stopEnc(); app.quit(); } },
  ]));
  tray.on('double-click', () => { if (win) win.show(); });
}

function nativeImageBlue() {
  const { nativeImage } = require('electron');
  // 16x16 solid #3D7EFF PNG
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAGklEQVR4AWP4//8/AzGYYVQhTCFMIUzhPxsAgL8D/QH0c6EAAAAASUVORK5CYII=',
    'base64'
  );
  return nativeImage.createFromBuffer(png);
}

app.whenReady().then(() => {
  createWindow();
  createTray();

  // IPC from renderer later: start/stop enc with settings
  const { ipcMain } = require('electron');
  ipcMain.handle('enc-start', (_e, opts) => {
    const args = [
      '--port', String(opts.port || 8091),
      '--fps', String(opts.fps || 30),
      '--bitrate', String(opts.bitrate || 20),
    ];
    if (opts.cropAr) args.push('--crop-ar', String(opts.cropAr));
    if (opts.width) args.push('--width', String(opts.width));
    startEnc(args);
    return true;
  });
  ipcMain.handle('enc-stop', () => { stopEnc(); return true; });
  ipcMain.handle('open-enc-dir', () => {
    shell.showItemInFolder(ENC);
  });
  ipcMain.handle('net-diagnose', async (_e, opts) => {
    const port = Number(opts?.port || 8091);
    return new Promise((resolve) => {
      const py = spawn('python', [
        path.join(__dirname, '..', 'run_host.py'),
        '--no-enc',
        '--port', String(port),
      ], { cwd: path.join(__dirname, '..') });
      let out = '';
      py.stdout.on('data', (d) => { out += d.toString(); });
      py.stderr.on('data', (d) => { out += d.toString(); });
      py.on('exit', () => {
        const m = out.match(/LAN IPs:\s*([^\n]+)/);
        const ip = m ? m[1].split(',')[0].trim() : '';
        const fwOk = !/denied|fail/i.test(out);
        resolve({ ip, port, fwOk, fwLog: out.trim() });
      });
      setTimeout(() => resolve({ ip: '', port, fwOk: false, fwLog: 'timeout' }), 8000);
    });
  });
  ipcMain.handle('list-res', () => {
    return new Promise((resolve) => {
      const py = spawn('python', [
        path.join(__dirname, '..', 'run_host.py'),
        '--list-res',
      ], { cwd: path.join(__dirname, '..') });
      let out = '';
      py.stdout.on('data', (d) => { out += d.toString(); });
      py.on('exit', () => {
        try {
          const line = out.split('\n').find((l) => l.trim().startsWith('['));
          resolve(JSON.parse(line || '[]'));
        } catch (_) {
          resolve([]);
        }
      });
      setTimeout(() => resolve([]), 8000);
    });
  });
  ipcMain.handle('save-config', (_e, cfg) => {
    const fs = require('fs');
    const p = path.join(__dirname, '..', 'config.json');
    fs.writeFileSync(p, JSON.stringify(cfg, null, 2), 'utf8');
    return true;
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  // keep tray on Windows
  if (process.platform !== 'darwin') {
    // do not quit — tray remains
  }
});

app.on('before-quit', () => stopEnc());
