# GL 磨砂背景交接文档（给开发 agent）

> 目标：胶囊按钮（变焦/模式/校准/全屏）与设置卡片，像 iOS 一样透出并**实时模糊 SurfaceView/TextureView 里的串流画面**。
> 状态：Demo 已验证（FrostBlurDemo v1.2，真机小米 15），本包即移植蓝本。

## 1. 结论先给

- **BlurView / RenderEffect 走不通**：SurfaceView 内容在独立 surface 上合成，视图树快照里没有它，官方与 issue 一致确认糊不上。凡是“必须糊 SurfaceView”的需求，只能自写 GL。
- **最终架构（v1.2，单解码零侵入旁路）**：

```
MediaCodec → Surface(TextureView/SurfaceView)   // 主显示链，一动不动，保颜色
   └─ 模糊旁路（独立 GL 线程，10–30fps 可配）：
      动画帧 → D0(1/2, 场景shader直画=下采样) → D1(1/4, Kawase) → U0(1/2, Kawase) → OUT(1/4, Kawase)
      → glReadPixels → Bitmap → FrostBlurView（按各自位置裁剪 + tint + 圆角）
```

- **真机数据（小米 15，强档 30fps）**：单次 `avg≈4ms`（预算 8ms 内），UI 掉帧率 0.23%，50 分位 5ms，主画面满帧。10fps 约 40ms/s GPU 时间，30fps 约 120ms/s，均无感。
- **PhoneCam 移植时**：把 Demo 里“场景 shader 画 JPEG”的输入，换成解码 `SurfaceTexture` 的 OES 纹理（`samplerExternalOES` + transform 矩阵），shader 链与 `FrostBlurView` 原样复用。详见 §4。

## 2. 硬约束（违反即打回）

1. 禁止改 `phonecam_enc.cpp` 的 NVENC / VUI / ARGB 路径。
2. 禁止改 `H264StreamClient` 的 `KEY_COLOR_RANGE`（保持 LIMITED，limited BT.709）。
3. 显示色彩必须与现网一致；模糊链只动 RGB 域，不要碰 YUV 范围。
4. 不要引入第二路网络解码；只动 Android 渲染层。
5. 1080p@30 下模糊单次 < 8ms；超限自动降档（逻辑已在 Demo 里，见 §3）。

## 3. Demo 说明（本包 frost-demo/）

纯 Java、无三方依赖，aapt + javac + dx 裸编（`build.sh` 一键出包）。包结构：

| 文件 | 作用 |
|---|---|
| `src/.../MainActivity.java` | 全屏 GLSurfaceView + 底栏胶囊 + 右上设置卡片；`pushBlur()` 分发全帧模糊图并回收上一帧 |
| `src/.../SceneRenderer.java` | GLSurfaceView.Renderer；主路直画屏幕；节拍到时跑模糊链 + `glReadPixels` + 翻转 + `Log.d("FrostBlur", "blur seq=N avg=Xms radius=L out=WxH")`；预热 30 帧内不降级，连续 3 次超 8ms 自动降弱档 |
| `src/.../KawaseFilter.java` | 全屏四边形 + 单 Kawase 程序（down/up 共用 4-tap 核，靠 `uOffset` 控半径）+ 场景 shader（平移/波浪/扫光/呼吸，专为验证“实时性”而做得显眼） |
| `src/.../FrostBlurView.java` | 磨砂容器（FrameLayout 子类）：先画模糊背景（按本 View 在画面中的位置裁剪）+ tint，再画子 View；无图时画半透明兜底色 |
| `res/drawable-nodpi/bg_scene.jpg` | 模拟串流画面的底图（移植时删掉，换真实解码帧） |
| `build.sh` | aapt → javac(Java 8, 无 lambda/dx 不支持 invokedynamic) → dx → aapt 打包 → uber-apk-signer 签名 |

设置接口（卡片上可直接点，移植时调 API 即可）：

- `renderer.setLevel(0/1/2)` → 关/弱/强；卡片按钮顺序 强→弱→关→强。
- `renderer.setIntervalMs(100/66/50/33)` → 10/15/20/30fps；卡片“帧率”按钮循环。
- `FrostBlurView.setBlur(bitmap, frameW, frameH, leftInFrame, topInFrame)`。

### 踩过的坑（别再踩）

1. **模糊链必须采样“动画帧”，不能采样静态源**（v1.1 血案）：Kawase 首 pass 若直接采原图纹理，动画 shader 只作用于屏幕那路，模糊图永远第一帧。修法：D0 由场景 shader 在半分辨率直画（采样+下采样一次完成），屏幕与模糊链同源。
2. **每帧不要多加全屏 pass**：v1.1 曾用全分辨率 sceneFBO 中转 + 拷贝，avg 从 5ms 涨到 9ms。半分辨率 D0 直画后回到 ~4ms。
3. **预热抖动会误触发降级**：shader 首次编译慢，EMA 余热高，前 30 个节拍跳过降级判定。
4. **多块磨砂共用一张图时，recycle 只能由分发处统一做**（MainActivity.lastBm），View 内部不许各自 recycle，否则画到一半被回收。
5. **dx 不支持 lambda**（无 invokedynamic）：Demo 代码刻意只用匿名内部类，`--release 8`。
6. dx 来自 apt `dalvik-exchange`（约 1MB），r8 不用下；签名用 nitron 自带的 uber-apk-signer。

## 4. 移植到 PhoneCam（开发 agent 按此做）

现状假设（已与需求方确认）：`MediaCodec → Surface(TextureView)`，`minSdk 26`，模糊区域固定（底栏胶囊+设置卡片），低频起步、高频可选。

1. 拷走 `FrostBlurView.java`、`KawaseFilter.java`（加一个 OES 场景 shader，见下）与 `SceneRenderer` 的链式/FBO/读回/降级逻辑；删掉 `bg_scene.jpg` 与 demo 场景 shader。
2. OES 输入：在解码输出的 `SurfaceTexture` 上 `setOnFrameAvailableListener`（节拍对齐复用现有监听，不要另起高频循环）；GL 线程内 `updateTexImage()` 后用下述 shader 画到 D0：
   ```glsl
   #extension GL_OES_EGL_image_external : require
   uniform samplerExternalOES sTex;
   uniform mat4 uTexMatrix;   // SurfaceTexture.getTransformMatrix()
   // horizontal/vertical flip 与旋转只用 uTexMatrix 表达，不要手写 UV 旋转叠加（CameraX/TextureView 系已 bake 旋转，叠加会转 90°）
   ```
   YUV→RGB 由驱动做，保持 limited 范围语义不变；tint 只在 `FrostBlurView` 做半透明罩，不进 GL。
3. 主显示链不动：继续用现有 TextureView（或 SurfaceView）显示；模糊旁路只读帧，不抢 `SurfaceTexture` 归属（OES 纹理 attach 冲突是MOT：只在一个 GL 上下文里 `updateTexImage`）。
4. 把 `MainActivity.pushBlur()` 的“按位置裁剪分发 + 统一回收”模式搬过去；胶囊/卡片用 `FrostBlurView` 包一层现有布局。
5. 度量：保留 `FrostBlur` logcat（`seq/avg/radius/out`），1080p@30 强档验收 `avg<8ms`；超限先降 30→15fps，再降半径，最后关模糊保流畅。

## 5. 验收

- [ ] 颜色与现网一致（主链未动，人眼 + 截图对比）。
- [ ] 胶囊/卡片透出画面且实时模糊（扫光/动效下肉眼可辨；`seq` 在 logcat 稳定推进）。
- [ ] 强档 30fps `avg<8ms`；弱网/低端机自动降级不断流。
- [ ] 切后台/旋转/回前台不崩，FBO 按 `onSurfaceChanged` 重建。

## 6. 本包内容

- `frost-demo/`：完整 Demo 工程（含 `build.sh`，输出 `download/FrostBlurDemo-v1.2.apk`）。
- `FrostBlurDemo-v1.2.apk`：可装验证包（小米 15 已验：实时糊、档位/帧率可切）。
- `docs/`：即本文档。
- `image/zzz_01.jpeg`：Demo 底图原图（移植时不需要）。
