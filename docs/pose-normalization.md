# 手机空间姿态规整化设计（PhoneCam）

目标：把 Android 原始传感器读数变成 **稳定、相对、可直接驱动 MC 相机** 的 yaw/pitch/roll。

---

## 1. 为什么现在的实现会「怪」

| 现象 | 常见原因 |
|------|----------|
| pitch 慢慢回 0 | 手写互补滤波用加速度硬拽倾角；重力系与设备系混用 |
| yaw 不明显 | 用 `getOrientation()` 的 azimuth 当绝对角，没做「校准四元数相对旋转」 |
| roll 无效/乱转 | MC `Camera.setRotation(yaw,pitch)` 不含 roll；且设备 Z-up ≠ 游戏 Y-up |
| 万向锁/轴交换 | 先转欧拉再 remap，顺序错误 |

**结论**：不要在「裸欧拉角」上做减法；应 **四元数相对化 → 再拆欧拉**。

---

## 2. 推荐数据管线

```text
Sensor (TYPE_GAME_ROTATION_VECTOR)
        │  q_raw  (device → Android world, Z-up)
        ▼
[1] 轴系统一：Android world(Z-up) → 游戏 look 空间(Y-up)
        │  固定旋转 R_fix = rotX(-90°) 等
        ▼
[2] 校准：保存 q0（F9 / 按钮）
        │  q_rel = conj(q0) ⊗ q_now     ← 相对姿态，永不过绝对北
        ▼
[3] 拆角：YXZ（FPS 惯用）
        │  yaw(Y) , pitch(X) , roll(Z)
        ▼
[4] 增益/死区/1€ 滤波
        │  yawGain, pitchGain, rollGain, deadzone°
        ▼
[5] 发包 UDP → Fabric Mod
        │
        ▼
[6] Mod：cameraYaw  = playerYaw  + yaw
           cameraPitch = clamp(playerPitch + pitch)
           rotation.rotateZ(-roll)
```

### 关键点

1. **相对四元数**（Daydream / OpenTrack / SlimeVR 都这么做）  
   - 校准瞬间为「看向正前方」  
   - 之后只关心 `q_rel`，无磁力计 yaw 漂移可接受，靠再校准

2. **轴系**  
   - Android world：X 东、Y 北、**Z 天**  
   - OpenGL / 多数游戏相机：**Y 上**、Z 前  
   - 官方建议 `SensorManager.remapCoordinateSystem` 或固定 90° X 旋转（见 Google VR `OrientationView`）

3. **欧拉顺序**  
   - 相机用 **YXZ**（先 yaw，再 pitch，再 roll）  
   - 与 MC `Quaternionf.rotationYXZ` 一致

4. **滤波**  
   - 系统 RV 已融合 → 轻量 1€ / slerp 即可  
   - 自研融合才用 Madgwick / Mahony / **VQF**（everything-imu-mobile 用 VQF）

---

## 3. 可参考实现

| 项目 / 文档 | 可抄什么 |
|-------------|----------|
| [Google Daydream `OrientationView`](https://chromium.googlesource.com/external/github.com/googlevr/gvr-android-sdk/+/master/samples/sdk-controllerclient/src/main/java/com/google/vr/sdk/samples/controllerclient/OrientationView.java) | RV → matrix → 90° X 轴转 Y-up；`resetYaw` 只补 yaw |
| Android 文档 [Game Rotation Vector](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position) | GRV 无磁、相对旋转更准；`getRotationMatrixFromVector` + `getOrientation` + `remapCoordinateSystem` |
| Google VR `HeadTransform` | 欧拉定义：pitch X、yaw Y、roll Z；不推荐欧拉做存储 |
| [everything-imu-mobile](https://github.com/matiaspalmac/everything-imu-mobile) | 手机当 tracker：原始 IMU 外传 + 桌面 VQF；shake-to-recenter；前台服务 |
| [VQF](https://github.com/dlaidig/vqf) | 高精度姿态融合（需要自研时的首选） |
| OpenTrack / SlimeVR | 协议与「中心校准 + 相对旋转」产品化流程 |
| StackOverflow: Game RV quaternion → Unity | 轴乱序的经典坑：必须 remap，不要先欧拉再拼 |

---

## 4. 本项目约定（写进协议 v1.1）

发送端（App）在 **游戏 look 空间** 输出：

| 字段 | 约定 |
|------|------|
| `yaw` | 相对校准的水平转头，**度**，MC 符号（右转为正？——与 `playerYaw + yaw` 对齐后以联调为准） |
| `pitch` | 相对校准俯仰，**度**，**上正下负**（Mod 侧再转 MC 的下正）或直接 MC 符号 |
| `roll` | 相对校准侧倾，**度**，画面倾斜 |
| `q` | 可选，相对四元数 `[w,x,y,z]`，Mod 可直接用 |
| `t` | 手机 `elapsedRealtime` ms |

**校准语义**：点击校准后，当前手机姿态 = 游戏当前视角为零点。

---

## 5. 增强清单（按优先级）

1. **相对四元数校准**（替换现在的欧拉减法）— 必须  
2. **Z-up → Y-up 固定变换** — 必须  
3. **1€ 滤波**（yaw/pitch/roll 分通道）  
4. **死区**（±0.5°～1°）防抖  
5. **摇一摇重校准**（对齐 eimu）  
6. 前台服务 + WakeLock（防息屏断流）  
7. 可选：VQF 自研（仅当系统 RV 不够用）  
8. 可选：ARCore 位置（6DoF，另开任务）

---

## 6. 实现落点

- App：`sensor/OrientationFusion.kt`（相对四元数 + 轴变换）  
- Mod：`CameraMixin`（player + 相对角 + roll）— 已改  
- 协议：`protocol/pose-v1.md`
