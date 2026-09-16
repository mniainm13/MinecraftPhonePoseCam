# PhoneCam 架构与模块边界

> 目标：多 agent 并行。成功标准 = **接口假设一致**，不是文件夹好看。

## 三大产品线

```
┌─────────────┐   ┌──────────────────┐   ┌─────────────────┐
│ A. Mod jar  │   │ B. 串流          │   │ C. 手机 App     │
│ A1 接收     │   │ B1 推流          │   │ C1 UI           │
│ A2 相机     │   │ B2 接收/解码     │   │ C2 拍照录屏(预留)│
│ A3 配置     │   │ B3 控制通道      │   │ C3 追踪         │
│ A4 角色(占位)│  │ B4 编码抽象(概念) │   │ C4 网络         │
└─────────────┘   └──────────────────┘   │ C5 设置流程     │
                                         │ C6 人脸(占位)   │
                                         └─────────────────┘
           ▲                    ▲                    ▲
           └──────────── protocol + interfaces ──────┘
```

## 端口与链路（现状）

| 端口 | 方向 | 协议文档 |
|------|------|----------|
| UDP 42424 | 手机 → Mod 位姿 | `protocol/pose-v1.md` |
| TCP 8091 | PC → 手机 H.264 | `docs/interfaces/stream-video-v1.md` |
| UDP 8092 | 手机 → PC 控制 | `docs/interfaces/stream-control-v1.md` |
| TCP 8093 | 预留音频 | 未实现 |

## 坐标系与单位（权威摘要）

| 量 | 单位 | 约定 |
|----|------|------|
| yaw/pitch/roll | 度 | MC：yaw 0=+Z 南；pitch + 向下 |
| pos | 米（格） | 校准锚点相对；右手 Y-up |
| zoom | 无量纲 | 1=原 FOV；>1 变窄；**Mod 不钳制**，手机 min/max 负责 |
| 视频 | Annex-B H.264 | limited BT.709 |
| 码率 | Mbps | 控制通道整数 |

完整字段见 `docs/interfaces/`。

## 跨模块依赖（只允许这些箭头）

```
C3 追踪 ──产出 Pose──▶ C4 网络 ──UDP──▶ A1 接收 ──PosePacket──▶ A2 相机
C1 UI   ──用户意图──▶ C5 设置 ──▶ C4 / C3
C5      ──控制──▶ B3 ──▶ B1
B1 ──TCP 8091──▶ B2 ──Surface──▶ C1 显示
```

禁止：A2 直接解析字符串；C1 直接 `DatagramSocket`；B1 改 pose 字段名。

## 未来占位（不实现，但边界先写）

| 模块 | 与谁切开 | 原因 |
|------|----------|------|
| A4 角色模型 | ≠ A2 相机 | 骨骼/朝向/第一三人称可见性 |
| C6 人脸/自拍 | ≠ C3 VIO | 关键点网络 vs 空间追踪 |
| B4 AMF/QSV | = B1 外壳可换 | 不要 NVENC 写死进业务 |
| 音频 | 独立 | 8093，以后再说 |

## 目录约定（App 解耦后）

```
android/.../app/
  MainActivity.kt          # 薄壳：装配，不写业务细节
  ui/                      # C1 只改这里 + res
  ui/bridge/               # C1↔功能 的接口（手机 agent 依赖面）
  net/                     # C4
  stream/                  # C4 视频客户端（属串流 B2 实现位）
  sensor/                  # C3
  capture/                 # C2 预留
```

## 多 agent 入口

1. 读 `AGENTS.md`  
2. 打开自己模块的 `docs/modules/<id>/HANDOFF.md`  
3. 需要协议细节 → `docs/interfaces/`  
4. 做完更新 HANDOFF；要改接口 → 找领头改文档再动代码  
