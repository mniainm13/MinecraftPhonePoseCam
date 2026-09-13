from pathlib import Path

# ASCII bat, CRLF, no BOM
Path(r"F:\workspace\mc-phone-cam\stream_host\PhoneCamStream.bat").write_bytes(
    b"@echo off\r\ncd /d \"%~dp0\"\r\npython run_host.py --config config.json\r\npause\r\n"
)
print("bat ok", Path(r"F:\workspace\mc-phone-cam\stream_host\PhoneCamStream.bat").read_bytes()[:8])

# default config 2K
import json
p = Path(r"F:\workspace\mc-phone-cam\stream_host\config.json")
c = json.loads(p.read_text(encoding="utf-8"))
c["width"] = 2560
c["height"] = 1440
c["crop_ar"] = None
p.write_text(json.dumps(c, indent=2), encoding="utf-8")
print("config", p.read_text())
