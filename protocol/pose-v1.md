# PhoneCam UDP Pose Protocol v1

权威源：本文件 + `docs/ARCHITECTURE.md` 坐标摘要。  
实现：C4 `UdpPoseSender` → A1 `PoseReceiver`。

- Transport: UDP，一包一 JSON 对象，UTF-8
- Default port: **42424**（Mod 绑 `0.0.0.0:42424`）
- Target: 与 Minecraft 客户端同一局域网

## Phone → PC（位姿）

```json
{
  "v": 1,
  "t": 1710000000123,
  "yaw": 12.5,
  "pitch": -3.2,
  "roll": 0.0,
  "pos": [0.0, 0.0, 0.0],
  "zoom": 1.0,
  "mode": "look"
}
```

| Field | Type | Unit | Meaning |
|-------|------|------|---------|
| `v` | int | — | 协议版本，当前 `1`；`!=1` 丢弃 |
| `t` | long | ms | 手机时间戳 |
| `yaw` | float | deg | 发送前已转到 MC 约定（0=+Z 南，+90=-X 西） |
| `pitch` | float | deg | 正向下（MC） |
| `roll` | float | deg | 度；M2+ |
| `pos` | float[3] | m（格） | 校准锚点相对位移；`[right, up, forward]` 手机本地再由 Mod 用玩家 yaw 转世界 |
| `zoom` | float | — | `1.0`=默认 FOV；`>1` 变窄。**Mod 不钳制范围**；`<=0/NaN` → 回 `1.0` |
| `mode` | string | — | `look` \| `window` \| `free`（行为见 A2 HANDOFF；现状以 look 为主） |

### 可选扩展（向后兼容）

| Field | Type | Meaning |
|-------|------|---------|
| `seq` | int | 发送侧自增；Mod 可统计乱序，不强制 |

## 坐标系（必须）

1. Android 设备轴 → **右手系 Y-up** 再发送。  
2. 首次连接 / 校准：Mod 存 yaw/pitch（及 pos 清零）为零点。  
3. `pos` 在 Mod 侧相对**玩家 yaw** 旋转到世界（非手机 yaw）。

## 发现（可选，未实现前勿当已存在）

- Phone 广播：`{"v":1,"type":"DISCOVER"}` → `255.255.255.255:42424`
- PC 单播：`{"v":1,"type":"HELLO","name":"PhoneCam","port":42424}`

## Mock

```bash
python tools/mock_sender.py --mode circle --hz 60
```

游戏内 **F8** 开跟踪，**F9** 校准。
