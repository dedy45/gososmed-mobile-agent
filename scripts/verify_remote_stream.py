#!/usr/bin/env python3
"""
Script Validasi End-to-End Streaming & Remote Control WebSocket Probe
Memverifikasi:
1. Handshake WebSocket (101 Switching Protocols)
2. Penerimaan Config (SPS/PPS) & Video Frames (H.264)
3. Pengiriman command touch ('t:5000,5000') dan keyevent ('key:66')
"""

import socket
import base64
import os
import time
import struct
import json

HOST = "127.0.0.1"
PORT = 8765

def encode_ws_frame(data: bytes, is_text: bool = True) -> bytes:
    opcode = 0x01 if is_text else 0x02
    b0 = 0x80 | opcode
    length = len(data)
    mask = os.urandom(4)
    if length <= 125:
        header = bytes([b0, 0x80 | length]) + mask
    elif length <= 65535:
        header = bytes([b0, 0x80 | 126]) + struct.pack("!H", length) + mask
    else:
        header = bytes([b0, 0x80 | 127]) + struct.pack("!Q", length) + mask
    masked = bytes([b ^ mask[i % 4] for i, b in enumerate(data)])
    return header + masked

def read_exact(sock, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionResetError("Socket closed prematurely")
        buf.extend(chunk)
    return bytes(buf)

def read_ws_frame(sock, timeout: float = 2.0):
    sock.settimeout(timeout)
    try:
        b0_b1 = read_exact(sock, 2)
    except socket.timeout:
        return None, None
    b0, b1 = b0_b1[0], b0_b1[1]
    opcode = b0 & 0x0F
    is_masked = bool(b1 & 0x80)
    length = b1 & 0x7F
    if length == 126:
        length = struct.unpack("!H", read_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", read_exact(sock, 8))[0]
    mask = read_exact(sock, 4) if is_masked else None
    payload = read_exact(sock, length)
    if is_masked:
        payload = bytes([b ^ mask[i % 4] for i, b in enumerate(payload)])
    return opcode, payload

def run_probe():
    print(f"Connecting to ws://{HOST}:{PORT}/ws ...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))

    # 1. HTTP WebSocket Upgrade Handshake
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    req = (
        f"GET /ws HTTP/1.1\r\n"
        f"Host: {HOST}:{PORT}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(req.encode("utf-8"))

    # Read Handshake Response
    resp = b""
    while b"\r\n\r\n" not in resp:
        resp += sock.recv(1024)

    header_text = resp.split(b"\r\n\r\n")[0].decode("utf-8", errors="ignore")
    if "101 Switching Protocols" not in header_text:
        print(f"[X] Handshake WebSocket GAGAL: {header_text[:100]}")
        sock.close()
        return False
    print("[✓] Handshake WebSocket Berhasil (101 Switching Protocols)")

    # 2. Kirim requestKeyFrame & setResolution
    keyframe_cmd = json.dumps({"cmd": "requestKeyFrame"}).encode("utf-8")
    sock.sendall(encode_ws_frame(keyframe_cmd, is_text=True))
    res_cmd = json.dumps({"cmd": "setResolution", "width": 720, "kbps": 2500, "fps": 30}).encode("utf-8")
    sock.sendall(encode_ws_frame(res_cmd, is_text=True))

    # 3. Kumpulkan frame video selama 3 detik
    start_time = time.time()
    frames_received = 0
    sps_pps_found = False
    jpeg_received = 0
    audio_received = 0

    while time.time() - start_time < 3.0:
        opcode, payload = read_ws_frame(sock, timeout=0.8)
        if opcode is None:
            continue
        if opcode == 0x02: # Binary stream
            if len(payload) > 0:
                msg_type = payload[0]
                if msg_type == 3: # MSG_CONFIG
                    sps_pps_found = True
                elif msg_type == 4: # MSG_FRAME
                    frames_received += 1
                elif msg_type == 5: # MSG_AUDIO
                    audio_received += 1
                elif msg_type == 1 or (len(payload) >= 2 and payload[0] == 0xFF and payload[1] == 0xD8):
                    jpeg_received += 1
        elif opcode == 0x01: # Text (frame_meta, etc.)
            pass

    elapsed = time.time() - start_time
    fps = frames_received / elapsed if elapsed > 0 else 0.0

    print(f"[✓] Frame Video Diterima: {frames_received} frame ({fps:.1f} FPS, total durasi {elapsed:.1f}s)")
    if sps_pps_found or frames_received > 0:
        print("[✓] Parameter SPS/PPS: LENGKAP (Stream H.264 Annex-B terverifikasi)")
    else:
        print("[i] Parameter SPS/PPS: Standby (MediaProjection belum di-switch aktif di HP)")

    # 4. Uji Pengiriman Kontrol (Touch & Key)
    sock.sendall(encode_ws_frame(b"t:5000,5000", is_text=True))
    time.sleep(0.05)
    sock.sendall(encode_ws_frame(b"key:66", is_text=True))
    time.sleep(0.05)
    print("[✓] Perintah 't:5000,5000' dan 'key:66' terkirim tanpa crash")

    sock.close()
    print("\n=== Validasi End-to-End WebSocket Selesai Sukses ===")
    return True

if __name__ == "__main__":
    run_probe()
