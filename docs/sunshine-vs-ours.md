# 为什么 Sunshine 快、我们的 MVP 慢（对照）

你实测：**同一热点，Sunshine+Moonlight，2K + 20Mbps 低延迟**。  
说明 **WiFi 够用**，差距在管线。

## Sunshine / Moonlight 快的原因

| 环节 | Sunshine | 我们当前 MVP |
|------|----------|--------------|
| 采集 | **DXGI Desktop Duplication**（GPU 纹理） | ffmpeg **gdigrab**（GDI，偏慢） |
| 编码 | 原生 **NVENC** D3D11 直喂 / avcodec 硬件帧 | ffmpeg `h264_nvenc`（多一层转） |
| 参数 | surfaces=1, delay=0, ull, CBR | 已尽量对齐，但采集源头更差 |
| 传输 | GameStream RTP，按帧/按包优化 | 原始 Annex-B **TCP** |
| 解码 | Moonlight 深度优化 | 我们自研 MediaCodec（能用，未极致） |

## 结论（可操作）

**短期（立刻可用、延迟接近 Sunshine）**  
- 画面：**Moonlight 看桌面**  
- 镜头：**PhoneCam 只发位姿**（UDP 42424）  
- 两个 App 并存；已验证网络能 2K/20Mbps  

**中期（仍要「一个 App」）**  
- 采集从 gdigrab 换 **DXGI**（Windows C++/Python 扩展，抄 `display_vram.cpp`）  
- 编码直接 NVENC API 或继续 ffmpeg 但避免 GDI  
- 手机侧：修好 **letterbox**（已做）、`LOW_LATENCY`、丢帧不排队  

**不要指望** 仅靠调 MJPEG/q/bitrate 追上 Sunshine。

## 拉伸问题

桌面是横屏，Surface 曾全屏竖屏硬拉 → 已按 SPS 宽高 **fit-center**。
竖屏满幅：PC 推 `--crop-ar 9:19.5 --width 720`。

## 推荐命令（自研链路调试用）

```powershell
python stream_server.py --mode h264 --fps 30 --width 960 --bitrate 20
# 竖屏满幅：
python stream_server.py --mode h264 --fps 30 --width 720 --bitrate 16 --crop-ar 9:19.5
```
