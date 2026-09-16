# Stream Video v1（TCP 8091）

> PC 推流主机 → 手机取景器。实现：B1 `stream_host` / B2 `H264StreamClient`。

## 传输

| 项 | 值 |
|----|-----|
| 协议 | TCP |
| 默认端口 | **8091** |
| 载荷 | H.264 **Annex-B**（起始码 `00 00 00 01`） |
| 色彩 | **limited BT.709**（decoder `KEY_COLOR_RANGE=limited`） |
| 分辨率 | 默认跟随桌面；编码宽高可由控制通道/配置影响 |
| 多客户端 | **单客户端**；新连接前旧连接应断开 |

## 非目标

- 不用 RTP/RTSP（MVP）
- 不在视频流内嵌 SEI 控制
- 不改 NVENC VUI / 色彩范围

## 实现检查点

- [ ] 首包可为 SPS/PPS/IDR 之一；解码器需能处理任意 Annex-B 切片
- [ ] `TCP_NODELAY` 打开
- [ ] 断开后 App 侧重连（见 stream-control / App 网络 HANDOFF）
