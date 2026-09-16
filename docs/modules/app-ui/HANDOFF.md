# HANDOFF · C1 App UI

| 项 | 值 |
|----|-----|
| 路径 | `android/**/ui/**`、`res/layout/**`、`res/drawable/**`、`res/values/**` |
| 桥接 | `android/**/ui/bridge/**` |
| Owner | **手机 UI agent** |

## 做了什么

- 相机壳：顶栏设置、底栏变焦 pill、模式 pill、快门
- 磨砂：`FrostBlurView` + `FrostBlurRenderer`（渲染偏显示层，归 UI 视觉）
- 设置浮层 + 磨砂浮动卡片（帧率/清晰度）
- 裁切/画幅 UI 绑定

## 已知问题

- `MainActivity` 仍偏厚（历史装配）；业务应走 bridge，UI 不直接碰 socket

## 下一步（UI agent 专属）

- [ ] 只改 `ui/**` 与 `res/**`
- [ ] 通过 `ViewfinderUiBridge` / `SettingsActions` 请求功能变更
- [ ] 不修改 `net/` `stream/` `sensor/`

## 边界

| 允许 | 禁止 |
|------|------|
| 布局、drawable、动画、磨砂视觉、pill | `DatagramSocket`、`MediaCodec`、ARCore Session |
| 读 bridge 状态展示 | 直接 `UdpPoseSender.send` |
| 调 bridge 方法 | 改 `H264StreamClient` / `ArCoreTracker` |
