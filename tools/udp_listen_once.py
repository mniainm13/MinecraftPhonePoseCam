#!/usr/bin/env python3
"""Listen for one PhoneCam UDP packet and print it. Run while phone streams."""
import socket
import sys

port = int(sys.argv[1]) if len(sys.argv) > 1 else 42424
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("0.0.0.0", port))
sock.settimeout(15)
print(f"Listening on 0.0.0.0:{port} for 15s...", flush=True)
try:
    data, addr = sock.recvfrom(2048)
    print(f"FROM {addr}: {data.decode('utf-8', errors='replace')}")
except socket.timeout:
    print("TIMEOUT: no packet received")
finally:
    sock.close()
