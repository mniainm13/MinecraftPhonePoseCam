#!/usr/bin/env python3
"""Mock phone sender for PhoneCam.

Sends UDP JSON pose packets to the Minecraft client mod.
No Minecraft required to test the network path.

Usage:
  python mock_sender.py                      # animate yaw slowly, port 42424
  python mock_sender.py --host 127.0.0.1 --port 42424 --hz 60
  python mock_sender.py --mode circle        # yaw/pitch circle
  python mock_sender.py --mode wave          # yaw sine + pitch sine
"""

from __future__ import annotations

import argparse
import json
import math
import socket
import time


def make_packet(t: float, mode: str) -> dict:
    if mode == "circle":
        yaw = math.sin(t) * 90.0
        pitch = math.cos(t * 0.7) * 20.0
    elif mode == "wave":
        yaw = math.sin(t * 1.5) * 45.0
        pitch = math.sin(t * 2.0) * 15.0
    else:  # spin
        yaw = (t * 40.0) % 360.0
        if yaw > 180:
            yaw -= 360.0
        pitch = 0.0

    return {
        "v": 1,
        "t": int(time.time() * 1000),
        "yaw": round(yaw, 2),
        "pitch": round(pitch, 2),
        "roll": 0.0,
        "pos": [0.0, 0.0, 0.0],
        "zoom": 1.0,
        "mode": "look",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="PhoneCam mock pose sender")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=42424)
    parser.add_argument("--hz", type=float, default=60.0)
    parser.add_argument("--mode", choices=("spin", "circle", "wave"), default="circle")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    interval = 1.0 / max(args.hz, 1.0)
    start = time.time()
    print(f"Sending {args.mode} pose to {args.host}:{args.port} at {args.hz:.0f} Hz. Ctrl+C to stop.")

    try:
        while True:
            t = time.time() - start
            packet = make_packet(t, args.mode)
            payload = json.dumps(packet, separators=(",", ":")).encode("utf-8")
            sock.sendto(payload, (args.host, args.port))
            if int(t * 2) != int((t - interval) * 2):
                print(f"[{t:6.2f}s] yaw={packet['yaw']:7.2f} pitch={packet['pitch']:6.2f}")
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\nstopped.")
    finally:
        sock.close()


if __name__ == "__main__":
    main()
