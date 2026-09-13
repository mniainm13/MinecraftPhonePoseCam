#!/usr/bin/env python3
"""PhoneCam Stream Host — low-latency desktop stream (Sunshine-style pipeline).

Pipeline: gdigrab desktop → h264_nvenc (ull) → Annex-B over TCP
Phone decodes with MediaCodec (scrcpy-like).

Inspired by Sunshine/deskstream/rvnc architecture, not a GameStream clone.

  python stream_server.py --mode h264 --port 8091 --width 1280 --fps 30
  python stream_server.py --mode h264 --crop-ar 9:19.5 --width 720
"""

from __future__ import annotations

import argparse
import os
import shutil
import socket
import subprocess
import sys
import threading

FFMPEG_DEFAULT = "ffmpeg"


def find_ffmpeg() -> str:
    env = os.environ.get("FFMPEG")
    if env and os.path.isfile(env):
        return env
    # Prefer 8.1 BtbN build: works with current driver 596.x NVENC (API 13.0).
    # PATH ffmpeg 9.x wants NVENC API 13.1 / driver 610+.
    for p in (
        r"F:\workspace\download\ffmpeg-8.1\ffmpeg-n8.1-latest-win64-gpl-8.1\bin\ffmpeg.exe",
        r"F:\workspace\tools\depth-map\ffmpeg\bin\ffmpeg.exe",
    ):
        if os.path.isfile(p):
            return p
    if shutil.which(FFMPEG_DEFAULT):
        return FFMPEG_DEFAULT
    raise SystemExit("ffmpeg not found; set FFMPEG")


def parse_ar(s: str) -> float:
    if ":" in s:
        a, b = s.split(":", 1)
        return float(a) / float(b)
    if "/" in s:
        a, b = s.split("/", 1)
        return float(a) / float(b)
    return float(s)


def build_ffmpeg(args: argparse.Namespace) -> list[str]:
    ffmpeg = find_ffmpeg()
    # Always full desktop for now (user request); window capture later.
    inp = [
        "-f", "gdigrab",
        "-framerate", str(args.fps),
        "-i", "desktop",
    ]

    filters = []
    if args.crop_ar:
        ar = parse_ar(args.crop_ar)
        filters.append(
            f"crop='if(gt(iw/ih,{ar:.6f}),ih*{ar:.6f},iw)':'if(gt(iw/ih,{ar:.6f}),ih,iw/{ar:.6f})'"
        )
    filters.append(f"scale={args.width}:-2")
    filters.append(f"fps={args.fps}")
    vf = ",".join(filters)

    if args.mode == "h264":
        # Aligned with Sunshine src/video.cpp nvenc common options
        # delay=0, forced-idr=1, zerolatency=1, surfaces=1, tune=ull, rc=CBR, bf=0
        gop = 1 if args.gop == 1 else max(args.fps, 1)
        enc_try = [
            "-c:v", "h264_nvenc",
            "-preset", "p1",
            "-tune", "ull",
            "-rc", "cbr",
            "-zerolatency", "1",
            "-surfaces", "1",
            "-delay", "0",
            "-forced-idr", "1",
            "-spatial-aq", "0",
            "-b:v", f"{args.bitrate}M",
            "-maxrate", f"{args.bitrate}M",
            "-bufsize", f"{max(1, args.bitrate // 4)}M",
            "-g", str(gop),
            "-bf", "0",
            "-f", "h264",
        ]
        enc_fallback = [
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            "-b:v", f"{args.bitrate}M",
            "-g", str(gop),
            "-bf", "0",
            "-x264-params", "nal-hrd=cbr:force-cfr=1",
            "-f", "h264",
        ]
        enc = enc_fallback if args.no_nvenc else enc_try
    else:
        enc = ["-q:v", str(args.quality), "-f", "mpjpeg"]

    return [
        ffmpeg, "-hide_banner", "-loglevel", "error",
        "-probesize", "32",
        "-analyzeduration", "0",
        *inp,
        "-vf", vf,
        *enc,
        "pipe:1",
    ]


def run_h264_tcp(args) -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(1)
    print(f"H.264 TCP on {args.host}:{args.port} — waiting for PhoneCam viewfinder", flush=True)

    while True:
        conn, addr = server.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 256 * 1024)
        print(f"Client {addr}", flush=True)
        cmd = build_ffmpeg(args)
        print("ffmpeg:", " ".join(cmd), flush=True)
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=10**6,
            )
        except FileNotFoundError:
            print("ffmpeg missing", flush=True)
            conn.close()
            continue

        def drain_err(p):
            try:
                for line in p.stderr:
                    if line:
                        sys.stderr.write("[ffmpeg] " + line.decode("utf-8", "replace"))
            except Exception:
                pass

        threading.Thread(target=drain_err, args=(proc,), daemon=True).start()

        # Prefer latest data: if TCP send would block, skip chunk (drop) — Sunshine-like
        conn.setblocking(False)
        dropped = 0
        try:
            assert proc.stdout is not None
            while True:
                chunk = proc.stdout.read(64 * 1024)
                if not chunk:
                    break
                view = memoryview(chunk)
                while len(view):
                    try:
                        n = conn.send(view)
                        view = view[n:]
                    except BlockingIOError:
                        dropped += 1
                        # drop remainder of this chunk; next chunk is newer
                        view = view[len(view):]
                    except OSError:
                        raise
            if dropped:
                print(f"dropped chunks (backpressure): {dropped}", flush=True)
        except (BrokenPipeError, ConnectionResetError, OSError) as e:
            print(f"send stop: {e}", flush=True)
        finally:
            proc.terminate()
            try:
                conn.close()
            except OSError:
                pass
            print("Client gone", flush=True)


def run_mjpeg(args) -> None:
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    cmd = build_ffmpeg(args)
    print("ffmpeg:", " ".join(cmd), flush=True)
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=10**6)

    def drain_err(p):
        try:
            for line in p.stderr:
                if line:
                    sys.stderr.write("[ffmpeg] " + line.decode("utf-8", "replace"))
        except Exception:
            pass

    threading.Thread(target=drain_err, args=(proc,), daemon=True).start()

    class H(BaseHTTPRequestHandler):
        def log_message(self, fmt, *a):
            sys.stderr.write("[mjpeg] " + (fmt % a) + "\n")

        def do_GET(self):
            self.send_response(200)
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
            self.end_headers()
            try:
                while True:
                    c = proc.stdout.read(4096)
                    if not c:
                        break
                    self.wfile.write(c)
            except (BrokenPipeError, ConnectionResetError):
                pass

    httpd = ThreadingHTTPServer((args.host, args.port), H)
    print(f"MJPEG on {args.host}:{args.port}", flush=True)
    try:
        httpd.serve_forever()
    finally:
        proc.terminate()
        httpd.server_close()


def main() -> None:
    p = argparse.ArgumentParser(description="PhoneCam stream host")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=None)
    p.add_argument("--mode", choices=("h264", "mjpeg"), default="h264")
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--width", type=int, default=960)
    p.add_argument("--bitrate", type=int, default=20, help="Mbps — hotspot can do ~20 like Sunshine")
    p.add_argument("--gop", type=int, default=0, help="0=fps (balanced), 1=every frame IDR (max recover, more bitrate)")
    p.add_argument("--quality", type=int, default=7, help="mjpeg only")
    p.add_argument("--crop-ar", dest="crop_ar", default=None, help="e.g. 9:19.5")
    p.add_argument("--no-nvenc", action="store_true", help="force libx264")
    # legacy flags ignored for desktop-only v1
    p.add_argument("--desktop", action="store_true", help="always on")
    p.add_argument("--title", default="Minecraft")
    args = p.parse_args()
    if args.port is None:
        args.port = 8091 if args.mode == "h264" else 8090

    if args.mode == "h264":
        run_h264_tcp(args)
    else:
        run_mjpeg(args)


if __name__ == "__main__":
    main()
