#!/usr/bin/env python3
"""Assemble standalone PhoneCamStream folder + zip (no Python on target)."""

from __future__ import annotations

import shutil
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent  # stream_host
PROJECT = ROOT.parent
OUT = ROOT / "PhoneCamStream"
DIST = PROJECT / "dist"
STAMP = datetime.now().strftime("%Y%m%d-%H%M%S")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    DIST.mkdir(parents=True, exist_ok=True)

    # ensure exe is next to Start.bat
    enc_src = ROOT / "native" / "phonecam_enc.exe"
    enc_dst = OUT / "phonecam_enc.exe"
    if enc_src.exists():
        if not enc_dst.exists() or enc_src.stat().st_mtime > enc_dst.stat().st_mtime:
            shutil.copy2(enc_src, enc_dst)
        print("ok phonecam_enc.exe")
    else:
        print("missing", enc_src)

    required = ["Start.bat", "config.json", "使用说明.txt", "phonecam_enc.exe"]
    for name in required:
        p = OUT / name
        print(("ok " if p.exists() else "missing ") + name)

    zip_path = DIST / f"PhoneCamStream-{STAMP}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for name in required:
            p = OUT / name
            if p.exists():
                z.write(p, f"PhoneCamStream/{name}")
    print("ZIP", zip_path, f"({zip_path.stat().st_size // 1024} KB)")

    # full release zip: stream + mod + apk
    jar = PROJECT / "dist" / "phonecam-0.1.0.jar"
    apk = PROJECT / "dist" / "PhoneCam-0.1.0-debug.apk"
    full = DIST / f"PhoneCam-release-{STAMP}.zip"
    with zipfile.ZipFile(full, "w", zipfile.ZIP_DEFLATED) as z:
        for name in required:
            p = OUT / name
            if p.exists():
                z.write(p, f"PhoneCam-release/stream/{name}")
        if jar.exists():
            z.write(jar, "PhoneCam-release/mod/phonecam-0.1.0.jar")
        if apk.exists():
            z.write(apk, f"PhoneCam-release/android/{apk.name}")
        readme = (
            "PhoneCam 完整包\n"
            "================\n"
            "1) stream/Start.bat  —— 管理员运行，推游戏画面到手机\n"
            "2) mod/phonecam-0.1.0.jar —— 丢进 .minecraft/mods/（需 Fabric API）\n"
            "3) android/*.apk —— 装到手机\n"
            "4) 手机与 PC 同一 WiFi；App 填 PC IP、端口 8091\n"
            "5) 游戏内 F8 开跟踪，F9 校准，F7 设置\n"
            "详见 stream/使用说明.txt\n"
        )
        z.writestr("PhoneCam-release/README-快速开始.txt", readme)
    print("FULL", full, f"({full.stat().st_size // 1024} KB)")

    # also refresh loose copies in dist/
    for src in (jar, apk):
        if src.exists():
            dst = DIST / src.name
            try:
                if not dst.exists() or src.stat().st_mtime > dst.stat().st_mtime:
                    shutil.copy2(src, dst)
                print("dist", src.name)
            except PermissionError:
                print("skip locked", src.name)

    print("DIST", DIST)


if __name__ == "__main__":
    main()
