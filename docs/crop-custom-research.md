# PhoneCam 自定义裁切 · 调研（未实现）

> 日期：2026-09-13  
> 现状：取景已用 GL `uCrop` 做 center-crop；可扩展为用户可选裁切。

## 目标

用户可自定义「只看桌面哪一块」：
- 拖拽裁切框（角/边）
- 比例预设：原始 / 16:9 / 9:16 / 1:1 / 自由
- 记住上次裁切，下次进 App 自动套用

## 数据流（与现架构兼容）

```
桌面 2560×1440
   → 编码前裁切（PC）或
   → 手机 GL uCrop UV（现状，零改 PC）
推荐：手机侧 UV 裁切，已打通，不碰 encoder
```

现 `FrostBlurRenderer.updateCrop()` 只做 center-crop；改成用户 `cropRect` 归一化 UV 即可。

## 旋转（滑条 + 输入，已实现）

- 设置内：**取景旋转** 滑条 -180°～180° + 右侧数字输入（一位小数）
- 滑条粗调，输入失焦后写回滑条并生效
- GL：`uRot` 在 crop UV 后绕框中心旋转，再乘 `uTexMatrix`
- 主路径与模糊链共用；prefs：`rotate_deg`
- 裁切框编辑器可复用同一套旋转控制

## UI 方案（参考 Android Camera2 CropView / Image-Cropper）

**方案 A — 轻量浮层（推荐）**
- 设置里「自定义裁切」开关 +「编辑裁切」
- 全屏半透明蒙层 + 可拖矩形（四角手柄）
- 比例 Chip：原始 / 16:9 / 9:16 / 1:1 / 自由
- 确认后写入 prefs：`crop_u0,v0,u1,v1`（0–1）
- GL 每帧用该 UV 替换 center-crop

**方案 B — 取景器内直接拖**
- 双指拖动/缩放裁切区（像手机相册）
- 手势易和现有 pinch-zoom FOV 冲突，需手势仲裁

**建议先做 A**，手势不和缩放打架。

## 实现要点

1. `CropEditActivity` 或 Dialog：预览一帧 + 拖框  
2. 触摸：四角命中检测（参考 Camera2 CropView `touchTolerance`）  
3. 锁比例时拖角只改一边  
4. 输出：`left/top/right/bottom` 归一化 → `uCrop`  
5. 模糊链与主路径共用同一 `uCrop`（已如此）

## 性能

零额外 GPU：只改现有 shader 的 `uCrop` 四个 float。

## 验收

- [ ] 拖框流畅，比例锁定正确  
- [ ] 主画面与磨砂裁切一致  
- [ ] 重启 App 保持上次裁切  
- [ ] 「原始」可一键恢复全幅 center-crop  
