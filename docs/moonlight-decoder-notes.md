# Moonlight Android 解码要点（对照我们）

来源：`moonlight-stream/moonlight-android`  
`MediaCodecDecoderRenderer.java` / `MediaCodecHelper.java` / `decoder-errata.txt`

## 与我们现在相关的结论

| 点 | Moonlight | 我们 |
|----|-----------|------|
| 解码器 | 挑 **硬件** + FEATURE_LowLatency；不用 `*sw*` | `createDecoderByType` 随便拿 |
| 高通低延迟 | `vendor.qti-ext-dec-low-latency.enable=1`<br>`vendor.qti-ext-dec-picture-order.enable=1` | 只设了 KEY_LOW_LATENCY |
| Xiaomi | 对 `vdec-lowlatency` 特殊处理 | 小米刷 `CSD is not calculated` |
| NVENC ref frames | errata：**num_ref_frames=16** 会卡/错；要压 ref | 未限制 `-refs` |
| SPS | 部分机型要 `max_dec_frame_buffering=1` | 未处理 |
| 输入 | GameStream 整帧 AU；带 SPS/PPS | Annex-B 单 NAL |
| 输出 | 专用线程 + 超时 dequeue + 渲染到 SurfaceHolder | drain 0 超时曾导致 Render:0 |

## 我们已落地

1. 码流内送 SPS/PPS（避免纯 csd 在小米上无输出）  
2. `setFixedSize(1280,720)`  
3. 输出 dequeue 带 5ms 超时  
4. 编码 `-refs 1`（errata）  
5. 解码器：优先硬件 + 高通 vendor 低延迟键（见下）

## 参考

- https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java
- https://github.com/moonlight-stream/moonlight-android/blob/master/decoder-errata.txt
