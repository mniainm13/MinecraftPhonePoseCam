# HANDOFF · C4 App 网络

| 项 | 值 |
|----|-----|
| 路径 | `android/**/net/**`、`android/**/stream/**` |
| 协议 | pose-v1 / stream-video-v1 / stream-control-v1 |
| Owner | app-net agent |

## 做了什么

- `UdpPoseSender`：缓存 InetAddress、手写 float 格式化、错误计数
- `StreamControlSender`：码率/帧率
- `H264StreamClient`：TCP Annex-B → MediaCodec → Surface

## 已知问题

- 控制端口 `port+1` 与规范 8092 需对齐
- 发现配对未做

## 下一步

- [ ] 端口对齐
- [ ] 可选 DISCOVER
- [ ] 对 UI 只暴露 bridge，不暴露 socket

## 边界

唯一碰网络的 App 包；UI 不得绕过本层。
