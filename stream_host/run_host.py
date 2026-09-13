#!/usr/bin/env python3
"""PhoneCam Stream Host — firewall + LAN IP + optional encoder.

Solves "port blocked / wrong IP" in one place. Config lives next to this script.

  python run_host.py
  python run_host.py --no-enc
  python run_host.py --list-res
  python run_host.py --width 1920 --bitrate 20
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys

DEFAULT_PORTS = {
    8091: "H.264 video TCP",
    8092: "stream control UDP",
    42424: "Minecraft pose UDP (mod)",
}


def app_dir() -> str:
    return os.path.dirname(os.path.abspath(__file__))


def lan_ips() -> list[str]:
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]


def ensure_firewall_ports(ports: dict[int, str]) -> None:
    for port, desc in ports.items():
        for proto in ("TCP", "UDP"):
            name = f"PhoneCam {proto} {port}"
            q = subprocess.run(
                ["netsh", "advfirewall", "firewall", "show", "rule", f"name={name}"],
                capture_output=True,
                text=True,
            )
            if q.returncode == 0 and "No rules match" not in (q.stdout + q.stderr):
                continue
            r = subprocess.run(
                [
                    "netsh", "advfirewall", "firewall", "add", "rule",
                    f"name={name}",
                    "dir=in", f"protocol={proto}", f"localport={port}",
                    "action=allow", "profile=any",
                    "enable=yes",
                ],
                capture_output=True,
                text=True,
            )
            ok = r.returncode == 0
            print(f"  firewall {proto}/{port}: {'ok' if ok else 'fail (run as Admin?)'}", flush=True)


def list_resolutions() -> list[dict]:
    native = None
    try:
        import dxcam
        cam = dxcam.create(output_idx=0)
        native = (int(cam.width), int(cam.height))
        cam.release()
    except Exception as e:
        print(f"dxcam: {e}", file=sys.stderr)
    presets = [
        (1280, 720, "720p"),
        (1920, 1080, "1080p"),
        (2560, 1440, "2K"),
        (3840, 2160, "4K"),
    ]
    out = []
    if native:
        out.append({
            "w": native[0],
            "h": native[1],
            "label": f"桌面原生 {native[0]}×{native[1]}",
            "native": True,
        })
    for w, h, name in presets:
        out.append({"w": w, "h": h, "label": f"{w}×{h} ({name})", "native": False})
    return out


def find_enc() -> str | None:
    here = app_dir()
    for p in (
        os.path.join(here, "native", "phonecam_enc.exe"),
        os.path.join(here, "phonecam_enc.exe"),
    ):
        if os.path.isfile(p):
            return p
    return None


def load_config(path: str | None) -> dict:
    p = path or os.path.join(app_dir(), "config.json")
    if not os.path.isfile(p):
        return {}
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print("config load fail", e, flush=True)
        return {}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--bitrate", type=int, default=20)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=0, help="0 = keep capture aspect")
    ap.add_argument("--crop-ar", dest="crop_ar", default=None)
    ap.add_argument("--no-enc", action="store_true")
    ap.add_argument("--no-fw", action="store_true")
    ap.add_argument("--list-res", action="store_true")
    ap.add_argument("--config", default=None)
    args = ap.parse_args()

    if args.list_res:
        print(json.dumps(list_resolutions(), ensure_ascii=False))
        return

    cfg = load_config(args.config)
    if cfg:
        args.port = int(cfg.get("port", args.port))
        args.fps = int(cfg.get("fps", args.fps))
        args.bitrate = int(cfg.get("bitrate", args.bitrate))
        args.width = int(cfg.get("width", args.width))
        args.height = int(cfg.get("height", args.height))
        args.crop_ar = cfg.get("crop_ar", args.crop_ar)
        print("loaded config.json", flush=True)

    print("PhoneCam Stream Host", flush=True)
    print("LAN IPs:", ", ".join(lan_ips()), flush=True)

    ports = dict(DEFAULT_PORTS)
    ports[args.port] = "H.264 video TCP"
    ports[args.port + 1] = "stream control UDP"

    if not args.no_fw:
        print("Ensuring Windows Firewall allow rules...", flush=True)
        ensure_firewall_ports(ports)

    for p, d in ports.items():
        print(f"  {p}/  {d}", flush=True)

    if args.no_enc:
        return

    enc = find_enc()
    if not enc:
        print("phonecam_enc.exe not found (looked in native/ and this folder)", flush=True)
        return

    cmd = [
        enc,
        "--port", str(args.port),
        "--fps", str(args.fps),
        "--bitrate", str(args.bitrate),
        "--width", str(args.width),
    ]
    if args.height:
        cmd += ["--height", str(args.height)]
    if args.crop_ar:
        cmd += ["--crop-ar", args.crop_ar]
    print("Launching:", " ".join(cmd), flush=True)
    os.execv(enc, cmd)


if __name__ == "__main__":
    main()
