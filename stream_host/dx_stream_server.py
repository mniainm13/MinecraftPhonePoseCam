#!/usr/bin/env python3
"""PhoneCam Stream Host — DXGI capture + NVENC (Sunshine-style, single-app path).

dxcam (Desktop Duplication) → raw BGR pipe → ffmpeg h264_nvenc → Annex-B TCP :8091

Much faster than gdigrab (GDI). Phone: PhoneCam viewfinder (MediaCodec).

  python dx_stream_server.py
  python dx_stream_server.py --width 1280 --fps 30 --bitrate 20
  python dx_stream_server.py --crop-ar 9:19.5 --width 720
"""

from __future__ import annotations

import argparse
import os
import shutil
import socket
import subprocess
import sys
import threading
import time

try:
    import dxcam
except ImportError:
    sys.exit("pip install dxcam")


def find_ffmpeg() -> str:
    env = os.environ.get("FFMPEG")
    if env and os.path.isfile(env):
        return env
    # 8.1 works with driver NVENC API 13.0 (PATH ffmpeg 9.x wants 13.1)
    for p in (
        r"F:\workspace\download\ffmpeg-8.1\ffmpeg-n8.1-latest-win64-gpl-8.1\bin\ffmpeg.exe",
        r"F:\workspace\tools\depth-map\ffmpeg\bin\ffmpeg.exe",
    ):
        if os.path.isfile(p):
            return p
    if shutil.which("ffmpeg"):
        return "ffmpeg"
    raise SystemExit("ffmpeg not found")


def parse_ar(s: str) -> float:
    if ":" in s:
        a, b = s.split(":", 1)
        return float(a) / float(b)
    return float(s)


def even(n: int) -> int:
    return n - (n % 2)


def find_start(buf: bytearray, from_idx: int) -> int:
    i = from_idx
    n = len(buf)
    while i < n - 3:
        if buf[i] == 0 and buf[i + 1] == 0 and buf[i + 2] == 1:
            return i
        if i < n - 4 and buf[i] == 0 and buf[i + 1] == 0 and buf[i + 2] == 0 and buf[i + 3] == 1:
            return i
        i += 1
    return -1


class StreamServer:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.ffmpeg = find_ffmpeg()
        self.camera = dxcam.create(output_idx=0, output_color="BGR", backend="dxgi")
        self.full_w = int(self.camera.width)
        self.full_h = int(self.camera.height)

        # Capture only the center crop — cuts raw bytes ~3-4x vs full desktop
        self.region = self._center_region()
        self.cap_w = self.region[2] - self.region[0]
        self.cap_h = self.region[3] - self.region[1]
        self.out_w = args.width
        self.out_h = self._out_h()
        print(
            f"DXGI full {self.full_w}x{self.full_h} region={self.region} "
            f"→ pipe {self.cap_w}x{self.cap_h} → encode {self.out_w}x{self.out_h}",
            flush=True,
        )

    def _center_region(self) -> tuple[int, int, int, int]:
        """Center crop of desktop to --crop-ar (or full frame)."""
        if not self.args.crop_ar:
            return (0, 0, self.full_w, self.full_h)
        ar = parse_ar(self.args.crop_ar)
        full_ar = self.full_w / self.full_h
        if full_ar > ar:
            # source wider → crop width
            w = even(int(round(self.full_h * ar)))
            h = self.full_h
        else:
            w = self.full_w
            h = even(int(round(self.full_w / ar)))
        left = (self.full_w - w) // 2
        top = (self.full_h - h) // 2
        return (left, top, left + w, top + h)

    def _out_h(self) -> int:
        return even(int(round(self.out_w * self.cap_h / self.cap_w)))

    def build_ffmpeg(self) -> list[str]:
        gop = 1 if self.args.gop == 1 else max(self.args.gop, self.args.fps)
        # Region already cropped — only scale
        vf = f"scale={self.out_w}:{self.out_h},fps={self.args.fps}"

        return [
            self.ffmpeg, "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo",
            "-pix_fmt", "bgr24",
            "-s", f"{self.cap_w}x{self.cap_h}",
            "-r", str(self.args.fps),
            "-i", "pipe:0",
            "-vf", vf,
            "-c:v", "h264_nvenc",
            "-profile:v", "high",
            "-level:v", "4.1",
            "-preset", "p1",
            "-tune", "ull",
            "-rc", "cbr",
            "-zerolatency", "1",
            "-surfaces", "1",
            "-delay", "0",
            "-forced-idr", "1",
            "-spatial-aq", "0",
            "-refs", "1",
            "-pix_fmt", "yuv420p",
            "-b:v", f"{self.args.bitrate}M",
            "-maxrate", f"{self.args.bitrate}M",
            "-bufsize", f"{max(1, self.args.bitrate // 4)}M",
            "-g", str(gop),
            "-bf", "0",
            "-f", "h264",
            "pipe:1",
        ]

    def serve_forever(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.args.host, self.args.port))
        srv.listen(1)
        print(f"H.264 TCP {self.args.host}:{self.args.port} — waiting for phone", flush=True)

        while True:
            conn, addr = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 128 * 1024)
            print(f"Client {addr}", flush=True)
            self._session(conn)

    def _session(self, conn: socket.socket) -> None:
        cmd = self.build_ffmpeg()
        print("ffmpeg:", " ".join(cmd), flush=True)
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=0,  # unbuffered — lower pipe latency
            )
        except FileNotFoundError:
            print("ffmpeg missing", flush=True)
            conn.close()
            return

        # Prefer realtime scheduling for capture/encode threads
        try:
            import ctypes
            handle = ctypes.windll.kernel32.GetCurrentProcess()
            ctypes.windll.kernel32.SetPriorityClass(handle, 0x00000100)  # HIGH_PRIORITY_CLASS
        except Exception:
            pass

        def drain_err(p):
            try:
                for line in p.stderr:
                    if line:
                        sys.stderr.write("[ffmpeg] " + line.decode("utf-8", "replace"))
            except Exception:
                pass

        threading.Thread(target=drain_err, args=(proc,), daemon=True).start()

        stop = threading.Event()
        sent = [0]
        dropped = [0]
        last_stat = [time.time()]

        def maybe_stat():
            now = time.time()
            if now - last_stat[0] >= 2.0:
                dt = now - last_stat[0]
                print(
                    f"encode {sent[0]/dt:.1f} fps  drop {dropped[0]}",
                    flush=True,
                )
                sent[0] = 0
                last_stat[0] = now

        def pump_encode():
            """DXGI region grab → ffmpeg stdin. Latest frame only."""
            interval = 1.0 / max(self.args.fps, 1)
            self.camera.start(
                target_fps=self.args.fps,
                video_mode=True,
                region=self.region,
            )
            try:
                last = 0.0
                stdin = proc.stdin
                while not stop.is_set():
                    # Prefer zero-copy view when available
                    try:
                        frame = self.camera.get_latest_frame_view()
                    except Exception:
                        frame = self.camera.get_latest_frame()
                    now = time.perf_counter()
                    if frame is None:
                        time.sleep(0.0004)
                        continue
                    if now - last < interval - 0.0008:
                        continue
                    last = now
                    try:
                        stdin.write(memoryview(frame).cast("B"))
                        sent[0] += 1
                    except BrokenPipeError:
                        break
                    except Exception:
                        dropped[0] += 1
            finally:
                try:
                    self.camera.stop()
                except Exception:
                    pass
                try:
                    proc.stdin.close()
                except Exception:
                    pass

        def pump_send():
            """Forward encoded Annex-B. Never write a partial NAL (causes rainbow artifacts)."""
            # Parse start codes; only send complete NALs; on backpressure drop whole NALs
            # until next IDR so the decoder recovers instead of decoding garbage.
            acc = bytearray()
            pending_idr = False
            conn.setblocking(True)
            conn.settimeout(0.05)
            try:
                while not stop.is_set():
                    chunk = proc.stdout.read(32 * 1024)
                    if not chunk:
                        break
                    acc.extend(chunk)
                    # split complete NALs (start code … next start code)
                    while True:
                        sc = find_start(acc, 0)
                        if sc < 0:
                            acc.clear()
                            break
                        sc2 = find_start(acc, sc + 3)
                        if sc2 < 0:
                            # keep from first start code
                            if sc > 0:
                                del acc[:sc]
                            break
                        nal = bytes(acc[:sc2])
                        del acc[:sc2]
                        ntype = nal[4] & 0x1F if len(nal) > 4 else 0
                        if ntype == 5:
                            pending_idr = False
                        if pending_idr and ntype != 7 and ntype != 8 and ntype != 5:
                            dropped[0] += 1
                            continue
                        try:
                            conn.sendall(nal)
                            maybe_stat()
                        except socket.timeout:
                            # network busy — skip non-IDR, wait for IDR
                            dropped[0] += 1
                            if ntype != 5:
                                pending_idr = True
                        except OSError:
                            stop.set()
                            return
            except Exception:
                stop.set()

        t1 = threading.Thread(target=pump_encode, daemon=True)
        t2 = threading.Thread(target=pump_send, daemon=True)
        t1.start()
        t2.start()

        try:
            while not stop.is_set():
                if proc.poll() is not None:
                    break
                # detect client disconnect
                try:
                    conn.setblocking(False)
                    peek = conn.recv(1, socket.MSG_PEEK)
                    if peek == b"":
                        stop.set()
                        break
                except BlockingIOError:
                    pass
                except OSError:
                    stop.set()
                    break
                time.sleep(0.05)
        except KeyboardInterrupt:
            stop.set()
        finally:
            stop.set()
            proc.terminate()
            try:
                conn.close()
            except OSError:
                pass
            print(f"session end sent={sent[0]}", flush=True)


def main() -> None:
    p = argparse.ArgumentParser(description="PhoneCam DXGI+NVENC stream host")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8091)
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--width", type=int, default=720)
    p.add_argument("--bitrate", type=int, default=12)
    p.add_argument("--gop", type=int, default=15, help="keyframe interval; smaller=更快从花屏恢复")
    p.add_argument("--crop-ar", dest="crop_ar", default="9:19.5", help="center-crop; 9:19.5=phone portrait, 16:9=desktop")
    args = p.parse_args()
    StreamServer(args).serve_forever()


if __name__ == "__main__":
    main()
