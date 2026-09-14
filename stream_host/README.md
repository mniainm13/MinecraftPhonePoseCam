# Stream Host

## 主路径（推荐）：原生 DXGI → NVENC

不走 Python/ffmpeg raw 管道；**GPU 纹理 → NVENC → TCP 8091**（对齐 Sunshine 思路）。

```powershell
cd stream_host/native
./build.ps1   # 需 VS C++ Build Tools + Windows SDK
# 产物：phonecam_enc.exe
```

用户侧推荐直接用 Release 里的 `PhoneCamStream` 便携包（`Start.bat` 一键启动），不必本地编译。

编码分辨率默认跟随桌面整屏；手机取景器连 **TCP 8091**（Annex-B）。

## 备用：Python dxcam + ffmpeg

无 NVENC / 需要竖屏裁切时可走 Python 路径：

```powershell
cd stream_host
python dx_stream_server.py --width 540 --bitrate 10
```

有 CPU 拷贝 + 进程 hop，延迟更高；可做竖屏 `--crop-ar`。

## 便携打包

```powershell
python stream_host/package_portable.py
# dist/PhoneCamStream-*.zip
# dist/PhoneCam-release-*.zip   stream + mod + apk
```

## 端口

| 端口 | 用途 |
|------|------|
| TCP 8091 | H.264 视频 |
| UDP 8092 | 码率/帧率控制（App 可调） |
| UDP 42424 | 位姿（mod） |
