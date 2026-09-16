# HANDOFF · C5 App 设置与校准流程

| 项 | 值 |
|----|-----|
| 路径 | 现多在 `MainActivity`；目标抽到流程类 |
| Owner | app-settings agent |

## 做了什么

- SharedPreferences：host、变焦范围、presets、bitrate、blur_*、crop_*、bottom_ui 等
- 校准按钮：`fusion.calibrate()` + `ar.calibrate()`

## 已知问题

- 与 UI 绑定过深；手机 UI agent 不应改本逻辑

## 下一步

- [ ] 流程方法收拢，MainActivity 只转发
- [ ] 校准同时清 PositionTracker（P1-4）

## 边界

不实现渲染/解码；只编排。
