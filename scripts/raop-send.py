#!/usr/bin/env python3
"""
A minimal classic-RAOP (AirPlay 1) sender, for testing a receiver.

Sends uncompressed L16/44100/2 with no encryption -- Shairport Sync accepts
both (rtsp.c: "a=rtpmap:96 L16/44100/2", and encrypted=0 when the SDP carries
neither a=aesiv nor a=rsaaeskey). That avoids needing an ALAC encoder or the
RSA key exchange, which is the whole reason this is short.

pyatv cannot do this: it probes "GET /info" first, which is AirPlay 2.
"""
import socket, struct, sys, time, wave

HOST, PORT = sys.argv[1], int(sys.argv[2])
WAV = sys.argv[3]
FRAMES = 352                      # what Shairport advertises in fmtp
SR = 44100

w = wave.open(WAV, 'rb')
assert w.getnchannels() == 2 and w.getsampwidth() == 2 and w.getframerate() == SR
pcm = w.readframes(w.getnframes())
w.close()

# local UDP sockets: control and timing. Shairport sends timing requests here.
ctrl = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); ctrl.bind(("", 0))
timing = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); timing.bind(("", 0))
ctrl_port, timing_port = ctrl.getsockname()[1], timing.getsockname()[1]

rtsp = socket.create_connection((HOST, PORT), timeout=10)
rtsp.settimeout(10)
cseq = 0
local_ip = rtsp.getsockname()[0]
url = f"rtsp://{local_ip}/1"

def req(method, extra="", body=""):
    global cseq
    cseq += 1
    h = [f"{method} {url} RTSP/1.0", f"CSeq: {cseq}",
         "User-Agent: raop_send/1.0",
         "Client-Instance: 0011223344556677"]
    if body:
        h += ["Content-Type: application/sdp", f"Content-Length: {len(body)}"]
    if extra:
        h.append(extra)
    msg = "\r\n".join(h) + "\r\n\r\n" + body
    rtsp.sendall(msg.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        d = rtsp.recv(4096)
        if not d:
            raise RuntimeError(f"{method}: connection closed")
        buf += d
    head = buf.split(b"\r\n\r\n")[0].decode(errors="replace")
    print(f"  {method} -> {head.splitlines()[0]}")
    return head

req("OPTIONS")

sdp = (f"v=0\r\no=iTunes {int(time.time())} 0 IN IP4 {local_ip}\r\ns=iTunes\r\n"
       f"c=IN IP4 {HOST}\r\nt=0 0\r\nm=audio 0 RTP/AVP 96\r\n"
       f"a=rtpmap:96 L16/44100/2\r\na=fmtp:96 {FRAMES} 0 16 40 10 14 2 255 0 0 44100\r\n")
req("ANNOUNCE", body=sdp)

setup = req("SETUP",
            f"Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;"
            f"control_port={ctrl_port};timing_port={timing_port}")
server_port = None
for line in setup.splitlines():
    if line.lower().startswith("transport:"):
        for part in line.split(";"):
            if part.strip().startswith("server_port="):
                server_port = int(part.split("=")[1])
print(f"  server audio port: {server_port}")
assert server_port, "no server_port in SETUP response"

req("RECORD", "Range: npt=0-\r\nRTP-Info: seq=0;rtptime=0")

# Answer timing requests so the player will start rather than wait for sync.
timing.settimeout(0)
def service_timing():
    try:
        while True:
            data, addr = timing.recvfrom(2048)
            if len(data) >= 4 and data[1] & 0x7F == 82:   # timing request
                now = time.time()
                secs = int(now) + 0x83AA7E80              # NTP epoch
                frac = int((now % 1) * (1 << 32))
                reply = struct.pack(">BBHI", 0x80, 0xD3, 7, 0)
                reply += data[24:32] if len(data) >= 32 else b"\0" * 8
                reply += struct.pack(">II II", secs, frac, secs, frac)
                timing.sendto(reply, addr)
    except BlockingIOError:
        pass

audio = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
seq, ts, ssrc = 0, 0, 0
bpf = 4
total = len(pcm) // (FRAMES * bpf)
print(f"  streaming {total} packets ({total * FRAMES / SR:.1f}s)")
start = time.time()
for i in range(total):
    chunk = pcm[i * FRAMES * bpf:(i + 1) * FRAMES * bpf]
    # L16 is network byte order; wave gives little-endian
    be = b"".join(chunk[j + 1:j + 2] + chunk[j:j + 1] for j in range(0, len(chunk), 2))
    hdr = struct.pack(">BBHII", 0x80, 0xE0 if i == 0 else 0x60, seq & 0xFFFF, ts, ssrc)
    audio.sendto(hdr + be, (HOST, server_port))
    seq += 1; ts += FRAMES
    service_timing()
    target = start + (i + 1) * FRAMES / SR
    d = target - time.time()
    if d > 0:
        time.sleep(d)
print("  done")
req("TEARDOWN")
