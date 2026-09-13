# Sunshine 源码要点（自研管线对照）

不依赖 Sunshine 产品；只抄 **采集 + 低延迟编码** 设计。

来源：`LizardByte/Sunshine` — `src/platform/windows/display_base.cpp`、`display_vram.cpp`、`src/video.cpp`、`src/nvenc/*`

---

## 1. Windows 采集（DDAPI）

| 做法 | 代码位置 | 我们怎么对齐 |
|------|----------|--------------|
| **DXGI Desktop Duplication** 为主 | `duplication_t::init` → `IDXGIOutput5::DuplicateOutput1` | ffmpeg `gdigrab desktop` 已是 GDI 近似；下一版可换 `dshow`/自写 DXGI |
| 优先 `IDXGIOutput5`（格式/WCG） | display_base.cpp | 无需第一期 |
| `SetMaximumFrameLatency(1)` | display_base.cpp | 减 GPU 队列延迟 |
| `SetGPUThreadPriority(7)`（需管理员） | display_base.cpp | 可选提权 |
| 帧超时返回 `timeout`，ACCESS_LOST → `reinit` | `next_frame` | 推流端遇 grab 失败应重启进程，不要卡死 |
| **不要**在采集线程做重活 | 整个 display_* | 编码/发送与采集解耦 |

`Windows.Graphics.Capture` 为次要路径（服务模式不完整），第一期不用。

---

## 2. NVENC 低延迟参数（`src/video.cpp`）

Sunshine 对 `h264_nvenc` / `hevc_nvenc` 的 **common options**：

```
delay = 0
forced-idr = 1
zerolatency = 1
surfaces = 1
tune = ULTRA_LOW_LATENCY (ull)
rc = CBR
preset = 配置项（现代：p1~p7；legacy：llhp 等）
coder = h264_coder（cabac/ac）
```

含义：

| 参数 | 作用 |
|------|------|
| `zerolatency=1` | 无延迟缓冲、尽量无 lookahead |
| `surfaces=1` | 编码器只留 1 个输入表面 |
| `delay=0` | 无额外帧延迟 |
| `forced-idr=1` | 关键帧强制 IDR，便于丢包恢复/秒开 |
| `tune ull` | 超低延迟调优 |
| `rc CBR` | 码率稳，利于 WiFi |

**ffmpeg 对齐写法**（我们的 `stream_host`）：

```text
-c:v h264_nvenc -preset p1 -tune ull -rc cbr
-zerolatency 1 -surfaces 1 -delay 0 -forced-idr 1
-g <fps> -bf 0 -b:v … -maxrate … -bufsize …
```

`bufsize` 不要过大（Sunshine 用 CBR padding 而非大缓冲）。

---

## 3. 传输（我们不做完整 GameStream）

Sunshine 用 GameStream（RTSP/RTP/ENet + AES）。我们只需要：

1. **丢旧帧，不排队** — 接收端/发送端阻塞时丢当前帧，永远追最新  
2. **TCP_NODELAY** 或 UDP + 可选 FEC  
3. **GOP=1**（每帧 IDR）最稳秒开，码率代价大；LAN 可 `g=fps`  
4. 视频与位姿分通道（我们已是 UDP 42424 vs TCP 8091）

---

## 4. Android 侧

对齐 deskstream/scrcpy：

- `MediaCodec` + `KEY_LOW_LATENCY=1` + `KEY_PRIORITY=0`  
- `csd-0/1` 来自 SPS/PPS  
- 解码忙则 **丢 NAL**，不堆积  
- Surface 直渲，不走 Bitmap

---

## 5. 下一步（按性价比）

1. **马上**：ffmpeg 参数按上表锁死；发送端非阻塞丢帧  
2. **短期**：`surfaces 1` / `delay 0` 实测；fps 30、720p–1080p  
3. **中期**：Windows 原生 DXGI + NVENC（C++/Python 扩展），对齐 display_vram  
4. **不必做**：GameStream 协议、配对、手柄、音频（除非产品需要）

---

## 6. 关键源码路径（GitHub）

- `src/platform/windows/display_base.cpp` — DDAPI init / next_frame  
- `src/platform/windows/display_vram.cpp` — DXGI→NVENC  
- `src/video.cpp` — encoder_t nvenc common options  
- `src/nvenc/nvenc_base.cpp` — 独立 NVENC session  
