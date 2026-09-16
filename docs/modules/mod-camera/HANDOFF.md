# HANDOFF · A2 Mod 相机

| 项 | 值 |
|----|-----|
| 路径 | `mod/**/camera/**`、`mod/**/mixin/**` |
| 依赖 | 仅 `PosePacket`（A1） |
| Owner | Mod-camera agent |

## 做了什么

- 双包插值 + 平滑 + 可选外推 `EXTRAP_MS`
- `smoothFrame()` 整帧采样，避免轴间撕裂
- `GameRendererMixin`：`fov / zoom`，**不硬钳** FOV
- 断流：500–1500ms `disconnectFade` 渐隐，之后恢复原版相机
- 校准：F9 清角度 + `resetSmooth`（pos 锚点见 P1-4 未完项）

## 已知问题

- `mode` 字段解析了但行为基本恒 look（用户确认暂不拆）
- 校准是否完整清 pos 平滑：`resetSmooth` 已清；手机侧 PositionTracker 原点是否同步需联调

## 下一步

- [ ] mock_sender 验收 FOV/断流
- [ ] mode 真区分（若产品要）

## 边界

**可以**：消费 `PosePacket`、mixin 相机/FOV  
**禁止**：自己 parse 字符串；改端口/字段名
