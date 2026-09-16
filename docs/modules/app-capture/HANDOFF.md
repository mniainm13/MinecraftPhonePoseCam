# HANDOFF · C2 拍照 / 录屏（预留）

| 项 | 值 |
|----|-----|
| 路径 | 建议 `android/**/capture/**`（尚未创建实现） |
| Owner | app-capture agent（未来） |

## 做了什么

- 无

## 边界（先写死）

- 只依赖：B2 画面出口（Surface/Bitmap 回调）+ C1 触发入口
- **禁止**：自己开第二路视频 TCP；绕过 C4 重连逻辑
- 实现时新增 `ui/bridge/CaptureActions`，UI 不直接 MediaCodec
