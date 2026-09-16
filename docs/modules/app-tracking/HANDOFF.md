# HANDOFF · C3 App 追踪（AR / IMU）

| 项 | 值 |
|----|-----|
| 路径 | `android/**/sensor/**` |
| Owner | app-tracking agent |

## 做了什么

- `ArCoreTracker`：离屏 EGL + Session，6DoF
- `OrientationFusion`：IMU 3DoF
- `PositionTracker`：位置辅助
- Manifest AR **optional**；不支持则 IMU 降级（MainActivity 编排）

## 已知问题

- 校准与 Mod F9 语义对齐仍在 P1
- Depth 默认开过；建议注释保持关闭策略（见 DECISIONS/截图）

## 下一步

- [ ] 统一输出 Pose 结构给 C4
- [ ] 与 C6 人脸严格分离

## 边界

不画 UI；不直接 UDP；不改协议字段名。
