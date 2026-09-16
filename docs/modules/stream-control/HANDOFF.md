# HANDOFF · B3 传输控制

| 项 | 值 |
|----|-----|
| 规范 | `docs/interfaces/stream-control-v1.md` |
| 现有实现 | `android/.../net/StreamControlSender.kt` + 主机侧 UDP |
| Owner | stream-control（可与 B1/C4 兼任） |

## 做了什么

- 码率/帧率 JSON UDP 已在用

## 已知问题

- 无正式规范文档（本文件写完后算有）
- 无 PING/STOP

## 下一步

- [ ] 主机与 App 端口统一到 8092
- [ ] 可选心跳

## 边界

控制语义两端共享；禁止 App UI 直接拼控制包字符串（走 C4）。
