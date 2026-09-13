# Stream Host

## 主路径（推荐）：原生 DXGI → NVENC

不走 Python/ffmpeg raw 管道；**GPU 纹理 → NVENC → TCP 8091**（对齐 Sunshine 思路）。

```powershell
cd F:\workspace\mc-phone-cam\stream_host\native
.\build.ps1
.\phonecam_enc.exe --port 8091 --fps 30 --bitrate 12
```

- 当前编码分辨率 = **桌面整屏**（如 2560×1440），尚未 GPU 缩放/竖屏裁切  
- 手机取景器连 **TCP 8091**（与 Python 版协议相同 Annex-B）  
- 验证：`head = 00 00 00 01 67 64 ...`（H.264 High）

工具链（均在 **F 盘**）：

| 路径 | 内容 |
|------|------|
| `F:\workspace\tools\vs` | VS 2022 C++ Build Tools |
| `F:\workspace\tools\nvenc-headers` | nvEncodeAPI.h（已改成 API 13.0 对齐驱动） |

## 备用：Python dxcam + ffmpeg

```powershell
cd F:\workspace\mc-phone-cam\stream_host
python dx_stream_server.py --width 540 --bitrate 10
```

有 CPU 拷贝 + 进程 hop，延迟更高；可做竖屏 `--crop-ar`。

## 端口

| 端口 | 用途 |
|------|------|
| TCP 8091 | H.264 视频 |
| UDP 42424 | 位姿（mod） |


## 延迟优化（已做）

| 措施 | 效果 |
|------|------|
| **DXGI region 居中裁切** | 只抓手机比例区域（~664×1440），不再整屏 2560×1440 进 pipe |
| 少一次 `tobytes()` | `memoryview` 直写 stdin |
| TCP_NODELAY + 小发送缓冲 | 少排队 |
| NVENC ull / refs=1 / bf=0 | 编码侧对齐 Sunshine |

仍不及 Moonlight 的部分：RTP/帧级丢包、解码器 Choreographer 对齐、DXGI 纹理直进 NVENC（零拷贝）。

再压：

```powershell
python dx_stream_server.py --fps 30 --width 540 --bitrate 10
```

## 架构

```
dxcam DXGI region crop → BGR pipe → h264_nvenc (high, yuv420p, ull)
  → TCP 8091 Annex-B → 手机 MediaCodec → Surface
```
