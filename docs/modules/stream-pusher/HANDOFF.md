# HANDOFF · B1 推流主机

| 项 | 值 |
|----|-----|
| 路径 | `stream_host/**` |
| 视频协议 | `docs/interfaces/stream-video-v1.md` |
| 控制协议 | `docs/interfaces/stream-control-v1.md` |
| Owner | stream-pusher agent |

## 做了什么

- 原生 `phonecam_enc`：DXGI + NVENC → TCP 8091 Annex-B
- 便携包 `package_portable.py`
- 备用 Python：`dx_stream_server.py`

## 已知问题

- NVENC-only（B4 抽象未落码）
- 控制端口：历史用 `video+1`；规范写死 8092，实现需对齐或标 legacy
- Electron UI / 旧 Python 推流已从仓库删除

## 下一步

- [ ] 控制收包与 8092 对齐
- [ ] （可选）`IEncoder` 接口注释

## 边界

不改 pose 字段；不改 App 解码细节。
