# 任务：SurfaceView 视频背板磨砂（自写 GL）

> 给独立 agent 实现。**不要**在做本任务时改推流编码 / 音频 / CSC 色彩管线。

## 背景

- PhoneCam 用 **SurfaceView + MediaCodec** 显示串流画面（与 Moonlight Android 同路）。
- 颜色已验收：**limited BT.709**，qti 解码器 `colorRange=2`。
- **BlurView / RenderEffect 无法模糊 SurfaceView**（官方：SurfaceView-based can't be blurred）。
- 目标：胶囊按钮（变焦/模式/校准/全屏）与设置卡片，能像 iOS 磨砂一样**透出并模糊游戏画面**。

## 性能对比（结论先给）

| | BlurView（API31+ RenderEffect） | 自写 GL Dual Kawase |
|--|--------------------------------|---------------------|
| 输入 | 视图树快照 | SurfaceTexture / 纹理 |
| 能否糊 SurfaceView | **不能** | **能** |
| 典型耗时 | ~1–3ms（仅 UI 层） | ~3–8ms @1080p（半分辨率 Kawase） |
| 额外拷贝 | 视图快照 | 解码→纹理一次；可复用 |
| 延迟 | 跟视图同步 | 约 +1 帧 |
| 风险 | 颜色/合成安全 | 色彩与解码路径需自管 |

**结论**：在「必须糊 SurfaceView」前提下，自写 GL 是合理路径；性能接近 BlurView，代价是多一层渲染管线。

## 推荐架构（方案 G-Lite）

```
MediaCodec (H.264)
    → SurfaceTexture (OES)
    → GL 纹理
    ├─ 主路径：fullscreen blit → 显示 Surface（或继续用 SurfaceView 只做旁路）
    └─ 旁路：downsample → Dual Kawase blur → 模糊纹理
UI 胶囊/卡片：用模糊纹理作背景 + 半透明 tint
```

更稳妥的落地顺序：

### Phase 1 — 旁路模糊纹理（推荐）
1. 解码仍输出到 **SurfaceTexture**（不是 SurfaceView）。
2. 每帧：`updateTexImage` → GL 纹理。
3. 半分辨率 FBO 链做 Dual Kawase（2–3 pass down / up）。
4. 结果纹理交给自定义 View 绘制（胶囊背景）。
5. 主画面用 TextureView **仅作显示**时颜色可能又变灰——**优先**：
   - **显示仍用 SurfaceView**（保颜色）；
   - **另开一条** MediaCodec 输出到 SurfaceTexture 只为模糊（双解码，费 GPU）；
   - 或：**单解码到 SurfaceTexture，再 blit 到 SurfaceView 的 Surface**（需要 EGL）。

### Phase 2 — 单解码 + EGL blit（若 Phase1 双解码太重）
```
MediaCodec → SurfaceTexture
  → EGL context
    → draw video to SurfaceView surface (full)
    → draw blur chain to FBO
    → 自定义 UI 采样 blur FBO
```
注意：SurfaceView 的 Surface 需要单独 EGL window surface；与 SurfaceTexture 共享 context。

## 硬约束（必须遵守）

1. **禁止**改动 `phonecam_enc.cpp` 的 NVENC / VUI / ARGB 路径。
2. **禁止**改动 `H264StreamClient` 的 `KEY_COLOR_RANGE`（保持 LIMITED）。
3. 显示色彩必须仍是 **limited BT.709**；模糊链若用 RGB shader，输入按 sRGB 解码后不要改 YUV 范围。
4. 不要引入第二路网络解码；只动 Android 渲染层。
5. 性能：1080p@30 下模糊链 **< 8ms**；掉帧时自动降 radius 或关模糊。

## 交付物

1. `docs/gl-blur-plan.md` — 最终选型（Phase1 / Phase2）与测量数据。
2. 可选实现：`android/.../ui/FrostBlurView.kt` + 最小 demo 接入 `MainActivity` 底栏胶囊。
3. 在 `logcat` 打：`blur avg=…ms radius=…`。
4. 验收：颜色与现网一致；胶囊能透出画面并模糊。

## 非目标

- 声音推流
- 全色域 / CSC
- Electron / mod
