# PhoneCam

> **Vibe coding** — 本项目主要由 AI 与开发者在对话中快速迭代完成（架构/协议/Mod/App/推流主机）。
>
> **构建标注** — 由 **小米 MiMo 桌面版 Beta** 构建。

用手机空间位姿（角度 / 位置 / 缩放）通过局域网控制 **Minecraft** 摄像机，并把游戏画面硬编推流到手机取景器。

| 组件 | 技术 |
|------|------|
| 游戏 | Minecraft **1.21.10** + Fabric Loader **0.19.5** + Fabric API |
| 位姿 | 手机 IMU / ARCore → UDP `42424` → Mod 插值平滑 |
| 画面 | PC DXGI + NVENC H.264 → TCP `8091` → 手机 MediaCodec + GL |
| 取景 | 竖/横屏裁切、画幅 letterbox、Kawase 磨砂 UI |

## 仓库结构

| 路径 | 内容 |
|------|------|
| `mod/` | Fabric 客户端 mod（相机位姿 / FOV） |
| `android/` | PhoneCam App（位姿发送 + 取景器） |
| `stream_host/` | 原生推流主机源码 + 便携包脚本（成品见 Release） |
| `protocol/` | 位姿 JSON 协议 |
| `docs/` | 分发说明与归档 |
| `tools/` | mock_sender 等联调工具 |
| `dist/` | 本地构建产物（不入库，见 Release） |

## 端口

| 端口 | 方向 | 用途 |
|------|------|------|
| TCP **8091** | PC → 手机 | H.264 视频 |
| UDP **8092** | 手机 → PC | 码率/帧率控制 |
| UDP **42424** | 手机 → MC | 位姿 JSON |
| TCP **8093** | 预留 | 音频（未实现） |

## 快速开始（用户）

1. PC：装 **Fabric 1.21.10** + **Fabric API**，把 `phonecam-0.1.0.jar` 丢进 `mods/`
2. PC：解压 `PhoneCamStream`（或完整 release 包），**管理员**运行 `Start.bat`（首次加防火墙）
3. 手机：安装 APK，与 PC **同一 WiFi**，App 填 PC 的 IPv4（`ipconfig` 查看）
4. App：校准 → 开始推流 → 打开取景器；游戏内 **F8** 开跟踪，**F9** 校准零点
5. 详细见包内 `使用说明.txt` 与 [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md)

## 构建

### Mod

```powershell
cd mod
# 使用本机 Gradle，或 ./gradlew 若已装 wrapper
gradle build
# 产物：mod/build/libs/phonecam-0.1.0.jar
```

### Android

```powershell
cd android
gradle assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

需要 Android SDK；`android/local.properties` 里的 `sdk.dir` 本机自动生成，勿提交。

### 推流主机

```powershell
cd stream_host/native
./build.ps1   # 需 VS C++ Build Tools + Windows SDK
```

便携包：

```powershell
python stream_host/package_portable.py
# dist/PhoneCamStream-*.zip  仅推流
# dist/PhoneCam-release-*.zip  stream + mod + apk
```

## 游戏内按键

| 键 | 作用 |
|----|------|
| **F8** | 开关视角跟踪 |
| **F9** | 当前手机姿态校准为零点 |
| **F7** | Mod 配置菜单 |

## 无真机联调

```powershell
python tools/mock_sender.py --mode circle --hz 60
```

进游戏按 F8，镜头应随正弦轨迹转动。

## 设计文档

- 架构与里程碑：[PLAN.md](PLAN.md)
- 位姿协议：[protocol/pose-v1.md](protocol/pose-v1.md)
- 归档快照：[docs/ARCHIVE-2026-09-13.md](docs/ARCHIVE-2026-09-13.md)

## License

MIT — 见 [LICENSE](LICENSE)。
