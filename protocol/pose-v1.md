# PhoneCam UDP Pose Protocol v1

Transport: UDP, one JSON object per datagram, UTF-8.
Default port: `42424` (mod binds `0.0.0.0:42424`).
Target: same LAN as the Minecraft client.

## Phone → PC (pose)

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

| Field | Type | Meaning |
|-------|------|---------|
| `v` | int | Protocol version, currently `1` |
| `t` | long | Phone timestamp (ms) |
| `yaw` | float | Degrees, MC convention after phone transform (0 = +Z / south, +90 = -X / west) |
| `pitch` | float | Degrees, positive looks down (MC convention) |
| `roll` | float | Degrees, optional for M2+ |
| `pos` | float[3] | Relative translation (meters) from calibration anchor. `[right, up, forward]` in phone local space |
| `zoom` | float | `1.0` = default FOV. `>1` zoom in (narrow FOV). Clamp `0.25`–`4.0` recommended |
| `mode` | string | `look` \| `window` \| `free` |

## Coordinate notes

1. Convert Android device axes to right-handed Y-up before sending.
2. On first connect / calibrate, the client stores yaw/pitch as the zero point.
3. `pos` is applied relative to camera yaw on the PC side (M2).

## Discovery (optional, later)

- Phone broadcasts: `{"v":1,"type":"DISCOVER"}` to `255.255.255.255:42424` or mDNS
- PC replies: `{"v":1,"type":"HELLO","name":"PhoneCam","port":42424}`

## Example mock

```bash
python tools/mock_sender.py --mode circle --hz 60
```

Then in Minecraft: press **F8** to enable tracking, **F9** to calibrate.
