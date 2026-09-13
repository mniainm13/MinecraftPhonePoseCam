# 分发说明

目标用户机：另一台 **Windows + NVIDIA GPU**，与 Minecraft / 手机同一局域网。

## 用户需要什么

| 组件 | 是否随包 | 说明 |
|------|----------|------|
| `phonecam_enc.exe` | 是（编好后拷贝） | 推流；需 NVIDIA 驱动（带 NVENC） |
| `phonecam-0.1.0.jar` | 是 | Fabric 客户端 mod |
| `PhoneCam-…-debug.apk` | 是 | 手机 App |
| Fabric Loader + Fabric API | 否（用户自装） | 1.21.10 / Loader 0.19.5+ |
| Java 21 | 否 | 跑 MC |
| VS Build Tools | 否 | 仅开发机编译 exe 用 |
| ffmpeg / dxcam | 仅备用 Python 路径 | 主路径 exe **不依赖** |

## 打包建议结构

```
PhoneCam-release/
  stream/phonecam_enc.exe
  mod/phonecam-0.1.0.jar
  android/PhoneCam-0.1.0.apk
  README.txt          # 端口、按键、防火墙
```

## 用户侧步骤（README 要写清）

1. 装 Fabric 1.21.10 + Fabric API，放入 `phonecam-0.1.0.jar`
2. 允许防火墙 **TCP 8091**（视频）、**UDP 42424**（位姿）
3. 手机与 PC 同一 WiFi
4. 运行 `phonecam_enc.exe --port 8091`
5. 手机装 APK → IP 填 PC 局域网地址 → 校准 → 推流 + 取景器
6. 游戏内 F8 开跟踪

## 开发机 vs 用户机

- 本机工具链在 `F:\workspace\tools\…` 只服务**编译**；**不要**写进用户 README 当必装路径。
- `native\build.ps1` 已用 `vswhere` / 常见路径探测 VS，可用 `VCVARS=` 覆盖。
- 发布包**不要**依赖 `F:\workspace\download\ffmpeg-8.1`。

## 还没做的（分发向）

- [ ] 原生 exe 自动选显示器 / `--display 0`
- [ ] GPU 竖屏裁切稳定进 release（当前开发中）
- [ ] 一键安装说明（中英）
- [ ] 版本号与 `--version`
