# 画面回传方案调研（M5）

目标：PC 游戏画面 → 手机，延迟尽量低，最好和 PhoneCam 姿态 UI 同一 App。

---

## 结论（先看这个）

| 路线 | 延迟 | 自研量 | 是否同 App | 建议 |
|------|------|--------|------------|------|
| **A. Sunshine + Moonlight Android** | 很低（约 1–3 帧） | 几乎 0 | 否（两个 App） | **先验证体验**用这个 |
| **B. 自研 H.264：ffmpeg NVENC → UDP → MediaCodec** | 低（约 50–120ms 可调） | 中 | **是** | **产品主路径** |
| **C. MJPEG HTTP（已写 MVP）** | 中（80–200ms） | 小 | 是 | 调试/兜底 |
| D. MC 内嵌 NDI mod | 低 | 中 | 否（需 NDI 接收端） | 备选，依赖 NDI Runtime |
| E. WebRTC 全套 | 最低 | 大 | 可 | 不必第一期做 |

**不要重复造轮子的部分**：采集+编码+低延迟传输，Sunshine/deskstream/rvnc 已验证；我们只做「轻量取景器客户端 + 与位姿同进程」。

---

## 现成方案

### 1. Sunshine + Moonlight（推荐先体验）

- **PC**：[LizardByte/Sunshine](https://github.com/LizardByte/Sunshine) — NVENC/AMF/QSV 硬编，GameStream 协议
- **手机**：[moonlight-android](https://github.com/moonlight-stream/moonlight-android)
- 局域网 5GHz 下体验接近串流主机；可只「看画面」
- **缺点**：独立 App，难和 PhoneCam 位姿 UI 叠成一体；有配对/虚拟手柄等多余能力

### 2. deskstream（架构最像我们）

[erdo-enes/deskstream](https://github.com/erdo-enes/deskstream)

```
Windows: DXGI Desktop Duplication → MF/NVENC H.264 → UDP+FEC
Android: MediaCodec async low-latency → SurfaceView
目标 glass-to-glass < 50ms
```

要点可抄：无 jitter 缓冲、丢旧帧、CBR、无 B 帧、IDR 按需。

### 3. rvnc（scrcpy 思路）

ffmpeg 硬编 → 裸 H.264 NAL over TCP → MediaCodec。USB 时用 `adb reverse`，WiFi 直连亦可。

### 4. MC-NDI-Remastered

Minecraft mod 直接输出 NDI（Devolay）。手机需 NDI 接收 App，集成差，作备选。

### 5. OpenStream / webscreen

方向不对（手机→PC）或依赖浏览器 WebRTC，非本阶段首选。

---

## 本项目落地

### 短期（已做）

- `stream_host/stream_server.py`：独立推流进程（可 exe 化），ffmpeg + MJPEG HTTP
- 端口默认 **8090**，地址 `http://<PC_IP>:8090/stream.mjpg`

### 中期（主路径）

```
ffmpeg: gdigrab(Minecraft) → h264_nvenc (zerolatency, CBR)
      → MPEG-TS / raw Annex-B over UDP :8091
Android: MediaCodec 解码 → SurfaceView（全屏取景）
PhoneCam App 同时继续 UDP 42424 发位姿
```

参数建议（对齐 deskstream）：

- 720p30 或 1080p30 起步
- bitrate 8–20 Mbps（热点 WiFi）
- `-tune zerolatency` / 无 B 帧 / GOP 短
- 手机解码 `KEY_LOW_LATENCY`

### 与位姿的分工

| 通道 | 端口 | 内容 |
|------|------|------|
| UDP 42424 | 位姿 JSON | 手机 → PC（已有） |
| TCP/UDP 8090+ | 视频 | PC → 手机（MJPEG→H.264） |

**不要**把视频和位姿挤同一 socket。

---

## 参考链接

- https://github.com/LizardByte/Sunshine
- https://github.com/moonlight-stream/moonlight-android
- https://github.com/erdo-enes/deskstream
- https://github.com/poloputoamo/MC-NDI-Remastered
- scrcpy / MediaCodec 低延迟解码实践（rvnc、deskstream README）
