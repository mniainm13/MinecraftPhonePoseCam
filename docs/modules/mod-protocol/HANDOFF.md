# HANDOFF · A1 Mod 协议接收

| 项 | 值 |
|----|-----|
| 路径 | `mod/src/main/java/dev/phonecam/net/**` |
| 协议 | `protocol/pose-v1.md` |
| Owner | Mod-protocol agent |

## 做了什么

- 手写 JSON 子集解析 → `PosePacket`（零依赖）
- 端口 42424 UDP 接收
- zoom 非法值回 1（钳制已上收到 A2/手机）

## 已知问题

- 无 `v!=1` 严格门闩（计划 P2）
- 无 `seq` 丢包统计
- 无解析单测

## 下一步

- [ ] `parse()` 单测 ≥7 用例
- [ ] 可选 `seq` 统计
- [ ] 禁止本包 import camera/mixin

## 边界

**可以**：JSON → `PosePacket`  
**禁止**：改 FOV、mixin、配置屏
