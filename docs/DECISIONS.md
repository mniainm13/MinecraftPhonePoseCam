# 决策记录

格式：`YYYY-MM-DD · 主题 · 结论 · 原因`

## 2026-09-13 · 推流编码 NVENC 优先
- **结论**：主路径 DXGI + NVIDIA NVENC；Python dxcam 仅备用。
- **原因**：局域网低延迟；本机有 RTX；桌面可用性足够 MVP。

## 2026-09-13 · 显示 limited BT.709
- **结论**：`KEY_COLOR_RANGE` limited，不改 CSC。
- **原因**：已验收颜色正确；避免二次转范围花屏/发灰。

## 2026-09-15 · 磨砂用自写 Dual Kawase
- **结论**：SurfaceView 无法被 BlurView 糊；旁路 OES→FBO→Bitmap。
- **原因**：真机 ~3ms；与 demo v2.1 对齐。

## 2026-09-15 · zoom 不由 Mod 硬钳
- **结论**：Mod 只拒绝 `<=0/NaN`；范围归手机 zoomMin/Max。
- **原因**：配置屏写明「Mod 不再钳制 FOV」。

## 2026-09-15 · ARCore optional + IMU 降级
- **结论**：manifest `camera.ar required=false`；不支持则 OrientationFusion。
- **原因**：非 ARCore 机可安装；PLAN 已定降级。

## 2026-09-16 · 多 agent 模块切分
- **结论**：三大产品线 + 控制通道横向层；UI 仅依赖 `ui/bridge`。
- **原因**：接口不一致是并行最大风险；UI 先隔离便于手机侧开发。

## 2026-09-16 · 产物不进 git
- **结论**：apk/jar/exe/zip 只进 Release。
- **原因**：仓库瘦身；历史不再膨胀。
