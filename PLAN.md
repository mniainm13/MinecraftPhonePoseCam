# PhoneCam — 手机空间位姿控制 Minecraft 摄像机

> 项目路径：`F:\workspace\mc-phone-cam`
> 目标：手机像 VR 设备一样，用空间位置 / 角度 / 缩放驱动游戏内摄像机

---

## 1. 版本基线（已核实）

| 组件 | 版本 | 说明 |
|------|------|------|
| Minecraft | **1.21.10** | Fabric 官方 meta 确认 stable |
| Fabric Loader | **0.19.5**（已确认） | 兼容 1.21.10；若你坚持 0.18.4 也可，无额外收益 |
| Fabric API | **0.138.4+1.21.10** | 与 1.21.10 配套 |
| Java | **21** | MC 1.21.x 要求 |
| Mod 侧 | 客户端 only | 摄像机是纯客户端属性，服务器无需装 mod |

会话标题里写过 1.21.1，按你最新消息统一为 **1.21.10**。

---

## 2. 核心思路

**三进程/三件套**（已定结构）：

```
┌──────────────────────┐
│ PhoneCam Android App │  一体：AR/IMU 位姿 + 取景器画面
│  发位姿 UDP 42424    │
│  收画面 TCP 8090/91  │
└──────────┬───────────┘
           │  WiFi 局域网
     ┌─────┴──────┐
     ▼            ▼
┌─────────┐  ┌──────────────────┐
│ Stream  │  │ Fabric Mod       │
│ Host    │  │ (客户端 jar)     │
│ 独立exe │  │ 只改 Camera/FOV  │
│ 采集编码│  │ 收位姿 → 视角    │
└─────────┘  └──────────────────┘
     运行于同一台 PC；Stream Host 可单独开关
```

| 组件 | 路径 | 职责 |
|------|------|------|
| **Stream Host** | `stream_host/` | 独立推流（ffmpeg 采集+编码），可 exe 化 |
| **Fabric Mod** | `mod/` | 只接收位姿、驱动摄像机 |
| **Android App** | `android/` | 位姿采集 + 游戏画面取景器（一体） |

- **手机**：传感器 → 相对 yaw/pitch/roll + zoom，UDP 42424
- **Mod**：不碰视频；只改 `Camera`
- **Stream Host**：不碰游戏状态；只推画面
- 解耦好处：推流挂了不影响控镜头；mod 更新不影响串流；以后可换 WebRTC/NDI 只动 Stream Host

---

## 3. 三种相机模式（递进）

| 模式 | DOF | 手机能力 | 体验 |
|------|-----|----------|------|
| **M1 头部转向** | 3DoF | 陀螺仪姿态 | 手机转 → 视角转，身体不动（FreeLook 加强版） |
| **M2 轻度平移** | 3DoF + 伪 6DoF | 姿态 + 手机前后/左右移动映射为相机平移 | 窗口式伪 VR（鱼缸效应） |
| **M3 自由相机** | 6DoF | ARCore 位姿 或 外置基站 | 相机真正离开玩家飞，像 Freecam + 手柄 |

**已拍板**：Android 优先；**MVP = 6DoF + 缩放**；Loader = **0.19.5**。

**现实约束（6DoF）**：纯 IMU 航位推算位置漂移极快，单独不可用。两条可行路线：

| 路线 | 做法 | 推荐度 |
|------|------|--------|
| **A. ARCore 6DoF** | 手机用 ARCore `Frame.camera.pose` 出位置+姿态 | 真 6DoF，需 Google Play 服务与兼容机型 |
| **B. 姿态 3DoF + 有限平移** | 陀螺融合姿态 + 双指/摇杆/屏内拖动映射平移 | 不依赖 ARCore，体验是「窗口」不是「全身 VR」 |

**建议默认走 A，B 作降级**：检测不到 ARCore 时自动落到 B，保证任何安卓机都能用缩放+转头。

---

## 4. 通信协议（草案）

自定义紧凑 UDP，JSON 或二进制均可；MVP 用 JSON 便于调试。

```json
{
  "v": 1,
  "t": 1710000000123,
  "mode": "look",
  "q": [0.01, 0.02, 0.00, 0.99],
  "yaw": 12.5,
  "pitch": -3.2,
  "pos": [0.0, 0.0, 0.0],
  "zoom": 1.0
}
```

| 字段 | 含义 |
|------|------|
| `q` | 四元数（w,x,y,z 或约定序）——优先，避免万向锁 |
| `yaw/pitch` | 度，便于 3DoF 调试 |
| `pos` | 相对锚点位移（米），M2/M3 用 |
| `zoom` | 1.0 = 原始 FOV；>1 变窄 FOV 或拉近轨道相机 |
| `t` | 手机时间戳，PC 侧做延迟补偿与丢包检测 |

**发现与配对**：手机广播 `DISCOVER` → Mod 回 `HELLO {port, name}`；或手动填 IP+端口；进阶用二维码。

**性能目标**：局域网 RTT < 15ms，包率 60–90Hz，插值缓冲 2 帧。

---

## 5. 技术选型建议

### 5.1 手机端

| 方案 | 优点 | 缺点 | 建议 |
|------|------|------|------|
| **Android + Kotlin** | Sensor API 成熟，ARCore 可选，本机可测 | iOS 不覆盖 | **MVP 首选** |
| Flutter 双端 | 一份代码 Android+iOS | IMU 精细控制弱，社区传感器插件质量不一 | 二期 |
| 仅网页 (WebXR/DeviceOrientation) | 零安装 | 权限/精度/后台差 | 仅做 Demo |

**MVP 手机侧最小能力**：
- `SensorManager` 读 `TYPE_GYROSCOPE` + `TYPE_ACCELEROMETER`（+ 磁力计）
- 互补滤波 / Madgwick / VQF 出四元数
- 前台服务持续推流
- 简单 UI：IP、端口、校准按钮、灵敏度、反转 Y

### 5.2 PC / Mod 端

- Fabric 客户端 mod（Java 21）
- 原生 `DatagramSocket` 收包（异步线程 → 缓存最新位姿）
- Mixin 挂 `Camera#update`（1.21.x）改写 yaw/pitch；M2/M3 再改 `Camera` 位置或投影矩阵
- 1€ 滤波 / 一阶低通抑制抖动
- Cloth Config + Mod Menu 配置
- 可选：本地 Python 中继（若只想先验证链路，不用碰 MC）

### 5.3 坐标系约定（必须先定死）

- 手机：Android 设备坐标 → 统一转到「右手系 + Y 上」
- MC：yaw 0 = +Z（南），pitch 正 = 向下（与常见 OpenGL 不同，要换算）
- 以「校准瞬间手机姿态」为原点，相对旋转映射到相机，避免绝对航向漂移

---

## 6. 现成参考项目（强烈建议先读再写）

### 架构最像（外部 tracker + UDP + Fabric 相机）

| 项目 | 价值 |
|------|------|
| [mkoeppen/minecraft-fishtank-vr](https://github.com/mkoeppen/minecraft-fishtank-vr) | **最接近**：外部 tracker → UDP JSON → 客户端 Fabric mod 改投影/相机。含 1€ 滤波、校准面板、投影矩阵实现、以及 26.x 的坑（`Camera.extractRenderState`、reversed-Z）。网络层与 mod 结构可直接抄骨架 |
| [ganzuul/Correct-Gaming-Posture](https://github.com/ganzuul/Correct-Gaming-Posture) | OpenTrack UDP 头追 → MC 相机平移；1€ 滤波；Fabric 客户端 only |

### 相机注入 / FreeLook / Freecam

| 项目 | 价值 |
|------|------|
| [Celibistrial/freelook](https://github.com/Celibistrial/freelook) / [freelook_for_clients](https://github.com/BigWingBeat/freelook_for_clients) | 头身分离 yaw/pitch；插值防晕 |
| [GlamArdor/perspective-mod-reborn](https://github.com/GlamArdor/perspective-mod-reborn) | 1.21.x 相机 mixin 现代写法（redirect 实体 yaw/pitch） |
| [ItsFelix5/CameraTweaks](https://github.com/ItsFelix5/CameraTweaks) | Freecam + 记忆点 + 第三人称 |
| [erichamers/Freecam](https://github.com/erichamers/Freecam) | 成熟 freecam：速度、三脚架、视角记忆 |
| [Mirsario/Minecraft-CameraOverhaul](https://github.com/Mirsario/Minecraft-CameraOverhaul) | 多版本相机 mixin 与配置习惯 |

### 手机 IMU → 电脑

| 项目 | 价值 |
|------|------|
| [matiaspalmac/everything-imu-mobile](https://github.com/matiaspalmac/everything-imu-mobile) | Android 手机当 IMU tracker，UDP 推流 + 发现 + 校准 + 前台服务，**手机侧可大量借鉴** |
| [UmerCodez/SensaGram](https://github.com/UmerCodez/SensaGram) | 简单 Android 传感器 JSON/UDP 推流，适合最快打通 |
| [Musaddiq625/minecraft_flutter_controller](https://github.com/Musaddiq625/minecraft_flutter_controller) | Flutter UDP 控制 MC 的完整小例子（协议/发现） |

### 黑客松方向相近（玩法灵感，工程较糙）

- [MakeUofT_Mimecraft](https://github.com/EshanSankar/MakeUofT_Mimecraft) — MPU6050 头追 + 合成鼠标
- [HT62026-MIRL](https://github.com/Solaror0/HT62026-MIRL) — ESP32 IMU + OpenCV 头姿控制 MC

**可组合策略**：`everything-imu-mobile` 的手机推流 + `fishtank-vr` 的 UDP 接收/相机改造 + `perspective-mod-reborn` 的 1.21 相机 mixin，能省掉大约 50–60% 摸索时间。

---

## 7. 仓库结构（建议）

```
F:\workspace\mc-phone-cam\
├── PLAN.md
├── protocol/
│   └── pose-v1.md          # 协议文档 + 示例
├── mod/                    # Fabric 客户端 mod
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/dev/phonecam/
│       ├── PhoneCamClient.java
│       ├── net/PoseReceiver.java
│       ├── net/PosePacket.java
│       ├── filter/OneEuroFilter.java
│       ├── camera/CameraController.java
│       └── config/PhoneCamConfig.java
├── android/                # 手机 App（Kotlin）
│   └── app/src/main/java/dev/phonecam/app/
│       ├── sensor/Fusion.kt
│       ├── net/UdpSender.kt
│       └── ui/MainActivity.kt
└── tools/
    └── mock_sender.py      # PC 上假手机，方便无真机联调
```

---

## 8. 里程碑

### M0 — 环境与脚手架（0.5–1 天）
- [ ] 建 Fabric 1.21.10 dev 环境（Loom、Yarn、Java 21）
- [ ] 空 mod 能进游戏、能按键开关
- [ ] `mock_sender.py` 往指定端口发假位姿
- [ ] Mod 收到包打 log

### M1 — 3DoF 头部转向（2–4 天）【第一个可玩点】
- [ ] Android 基础 App：陀螺+加速度融合，60Hz 推流
- [ ] Mod：yaw/pitch 应用到相机（FreeLook 模式）
- [ ] 校准（手机水平朝前 = 基准）
- [ ] 1€ 滤波 + 灵敏度/反转配置
- [ ] 断流自动回原视角

### M2 — 缩放 + 体验打磨（1–2 天）
- [ ] 手机双指捏合 / 滑条 → FOV 或第三人称距离
- [ ] 平滑过渡、防晕（限制最大角速度）
- [ ] Mod Menu 配置页
- [ ] HUD 连接状态指示

### M3 — 伪 6DoF 平移 / 自由相机（3–7 天）
- [ ] 手机位移 → 相机平移（世界尺度可调）
- [ ] Freecam 模式：相机与玩家分离（参考 Freecam mod）
- [ ] 可选 ARCore 6DoF
- [ ] 三脚架/记忆机位（可选）

### M4 — 发布质量（2–3 天）
- [ ] 配对体验（发现/二维码）
- [ ] 协议版本兼容
- [ ] 打包 jar + APK 说明
- [ ] 与 Sodium/Iris 等兼容性抽查

### 优先级（已更新）

1. **串流延迟/卡顿**（当前）— 优化 dxcam+ffmpeg；原生 DXGI→NVENC 需 MSVC，**不装 C 盘**，暂缓
2. **6DoF** — ARCore 或 IMU+缩放平移，Mod 已有 pos 通道
3. **声音推流** — 列后（WASAPI loopback → AAC/Opus）
4. 磁盘：依赖优先 `F:\workspace\tools`，见全局 AGENTS.md

### M5 — 画面回传手机（「手持手机在游戏里拍摄」）（5–10 天）
- [ ] PC 侧采集游戏画面（Windows Graphics Capture / MC framebuffer 截帧）
- [ ] 编码：H.264 硬编（NVENC，本机 RTX 5070 Ti）
- [ ] 低延迟推流：WebRTC 或 MJPEG over HTTP（MVP 可先 MJPEG）
- [ ] 手机全屏播放 + 叠加位姿 UI；开「摄像机模式」时桌面可不看屏
- [ ] 端到端延迟目标：< 80–120ms（局域网）
- [ ] 分辨率/码率档位：720p30 起步，1080p60 优化

参考：OBS Studio 插件、Moonlight/Sunshine（GameStream）、scrcpy 逆向思路、WebRTC datachannel 兄弟流。

### 姿态规整化（进行中）
- [x] 文档 `docs/pose-normalization.md`
- [x] 相对四元数校准 + Z-up→Y-up + 1€ 滤波（Android）
- [ ] 真机验证轴向/灵敏度/roll
- [ ] 摇一摇重校准、前台服务防杀

---

## 9. 风险与坑

1. **坐标系与 pitch 符号** — 先用 `mock_sender` + 屏幕角落显示数值验证，再接真机
2. **漂移** — 仅 IMU 必漂；定期「重新校准」热键；有磁力计时做 yaw 融合
3. **延迟** — WiFi 路由器差时体验崩；插值缓冲略增可换平滑但加延迟，要可配
4. **MC 版本 API 变动** — 1.21.9→1.21.11 `Camera.update` 签名有变；目标锁 1.21.10，mixin 尽量按名字匹配
5. **不要 hook 错地方** — fishtank-vr 经验：26.x 改在 `Camera`；1.21.x 优先 `Camera#update` / 实体旋转 redirect
6. **反作弊** — 仅视角客户端本地改动，一般服务器安全；若做「控制玩家移动」才可能违规，本项目默认不做

---

## 10. 推荐立即执行顺序（已确认方向）

1. 初始化 Fabric **1.21.10 / Loader 0.19.5** 工程到 `mod/`
2. 写 `tools/mock_sender.py` + Mod 空收包器，打通 UDP
3. 用假数据驱动 yaw/pitch/pos/zoom，镜头跟手
4. 再写 Android：ARCore 优先，无则 IMU 降级
5. 缩放捏合 → 滤波与配对体验

---

## 11. 关键选择（已拍板）

| 项 | 决定 |
|----|------|
| 手机平台 | **Android 优先**（iOS 二期） |
| MVP 范围 | **6DoF + 缩放**（ARCore 主路径，IMU 降级） |
| Fabric Loader | **0.19.5** |
| Minecraft | **1.21.10** + Fabric API **0.138.4+1.21.10** |
