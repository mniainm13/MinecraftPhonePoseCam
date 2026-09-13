# PhoneCam 声音推流调研（未实现）

参考：Sunshine / Moonlight（LizardByte）

## Sunshine 音频架构

```
WASAPI loopback / PulseAudio / CoreAudio
        ↓ float PCM 48kHz
   sample queue (30 slots)
        ↓
  encodeThread: opus_multistream_encode_float
        ↓ Opus CBR LOWDELAY
  mail::audio_packets → RTP/UDP 到 Moonlight
```

关键参数（立体声普通档）：
- 采样率 **48000**
- 声道 **2**
- 码率 **96 kbps**（高质量 512 kbps）
- 帧长 = `packetDuration * 48000 / 1000`（常见 5ms / 10ms）
- Opus：`OPUS_APPLICATION_RESTRICTED_LOWDELAY`，VBR=0

## 与我们现状对比

| | Sunshine | PhoneCam 当前 |
|--|----------|---------------|
| 采集 | 专用 mic 接口 + 虚拟 sink | WASAPI loopback（系统混音） |
| 编码 | Opus 48k | 裸 PCM s16 |
| 传输 | UDP 加密隧道 | TCP 8093 |
| 手机 | Moonlight 解 Opus | AudioTrack 裸 PCM |

## 建议实现路径（分两阶段）

### 阶段 A — 先稳（1–2 小时）
1. WASAPI loopback + **AUTOCONVERTPCM → 48k s16**
2. 严格按实际 init 格式读包（禁止用 mix format 误读）
3. 帧长 **20ms**（960 samples @48k）打成一块
4. TCP 简单协议：`[magic 'PCAU'][seq u32][bytes u32][pcm]`
5. 手机 AudioTrack 48k stereo s16，缓冲 ≥ minBuf*4
6. 断线自动重连

### 阶段 B — 对齐 Sunshine（半天）
1. libopus 编码 48k stereo 96kbps LOWDELAY
2. 手机 MediaCodec AAC/Opus 解码 → AudioTrack
3. 带宽从 ~1.5Mbps 降到 ~12–96kbps

## 不要再犯的错
- 不要在加音频时改视频编码路径
- 不要用 GetMixFormat 的 192k float 去读 AUTOCONVERT 后的 48k 缓冲
- 一次只 accept 一个视频客户端；测试客户端会抢手机的槽
