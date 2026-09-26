#!/usr/bin/env python3
"""Mac console for the ESP32 over USB — DESIGN.md §4.1, protocol/PROTOCOL.md.

Keeps the failsafe fed with DRIVE at 40 Hz, pings at 1 Hz, prints LOG/ACK and
(optionally) TELEMETRY. Uses the reference codec from protocol/gen_vectors.py.

    pip install pyserial
    python3 tools/usb_console.py [--port /dev/cu.usbmodemXXXX]

Type `help` at the prompt. First runs: wheels in the air (DESIGN.md §7).
"""

import argparse
import shlex
import sys
import threading
import time
from pathlib import Path

import serial
from serial.tools import list_ports

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "protocol"))
import gen_vectors as proto  # noqa: E402

ESP_VID, ESP_PID = 0x303A, 0x1001
DRIVE_HZ = 40
DEFAULT_LIMIT = 0.3  # speed limit until measured (DESIGN.md §2.2)

T = proto.NAME_TO_TYPE
TYPE_NAMES = {t: v[0] for t, v in proto.TYPES.items()}
RESET_REASONS = {0: "unknown", 1: "power-on", 2: "external", 3: "software", 4: "panic",
                 5: "int wdt", 6: "task wdt", 7: "wdt", 8: "deep sleep", 9: "brownout", 10: "sdio",
                 11: "usb", 12: "jtag"}

HELP = """\
commands (speeds −1..1, scaled by `limit`):
  d VX VY W        drive continuously (vx right, vy forward, w clockwise)
  f/b/l/r [S]      forward/back/strafe left/right at S (default 0.5)
  cw/ccw [S]       rotate
  raw M1 M2 M3 M4  MOTOR_RAW on physical channels (bypasses calibration)
  idle             DRIVE with enable=0 (heartbeat only)
  stop | s         STOP ×3, then idle
  drop             stop sending: measures time until failsafe
  limit X          speed limit 0..1 (now {limit})
  hello | ping     handshake / round-trip time
  cfg              GET_CONFIG
  set K=V ...      change config fields and send CONFIG (e.g. set invert=0,1,0,1 min_duty=20)
  tel [on|off]     print TELEMETRY
  raw-log          print every received frame
  reboot           REBOOT
  quit | q         STOP and exit
"""


class Console:
    def __init__(self, port):
        self.ser = serial.Serial()
        self.ser.port = port
        self.ser.baudrate = 115200
        self.ser.timeout = 0.05
        # Never toggle DTR/RTS: the C6 USB-Serial/JTAG may reset into the bootloader.
        self.ser.dtr = False
        self.ser.rts = False
        self.ser.open()

        self.lock = threading.Lock()
        self.seq = 0
        self.mode = "idle"  # idle | drive | raw | silent
        self.cmd = (0.0, 0.0, 0.0)
        self.raw = (0, 0, 0, 0)
        self.limit = DEFAULT_LIMIT
        self.show_tel = False
        self.show_all = False
        self.config = None
        self.last_tel = None
        self.drop_at = None
        self.ping_sent = {}
        self.rtt_ms = None
        self.running = True

    # --- tx ---------------------------------------------------------------

    def send(self, type_name, fields=None):
        t = T[type_name]
        payload = proto.encode_payload(t, fields or {})
        with self.lock:
            frame = proto.encode_frame(t, self.seq, payload)
            seq = self.seq
            self.seq = (self.seq + 1) & 0xFF
            self.ser.write(frame)
        return seq

    def heartbeat(self):
        period = 1.0 / DRIVE_HZ
        next_ping = next_tick = time.monotonic()
        while self.running:
            mode = self.mode
            if mode == "drive":
                vx, vy, w = (to_i8(v * self.limit) for v in self.cmd)
                self.send("DRIVE", {"flags": 0x01, "vx": vx, "vy": vy, "w": w})
            elif mode == "raw":
                self.send("MOTOR_RAW", {"m": [to_i8(v * self.limit) for v in self.raw]})
            elif mode == "idle":
                self.send("DRIVE", {"flags": 0x00, "vx": 0, "vy": 0, "w": 0})
            now = time.monotonic()
            if now >= next_ping:
                ts = int(now * 1000) & 0xFFFFFFFF
                self.ping_sent[ts] = now
                self.send("PING", {"ts": ts})
                next_ping = now + 1.0
            next_tick = max(next_tick + period, now)  # fixed schedule, no drift
            time.sleep(max(0.0, next_tick - time.monotonic()))

    def stop(self):
        self.mode = "idle"
        for _ in range(3):
            self.send("STOP")

    # --- rx ---------------------------------------------------------------

    def reader(self):
        parser = proto.Parser()
        seen = 0
        while self.running:
            try:
                data = self.ser.read(256)
            except serial.SerialException as e:
                print(f"\n[usb] {e}; reconnect and restart the console")
                self.running = False
                return
            if not data:
                continue
            parser.feed(data)
            for t, seq, payload in parser.frames[seen:]:
                self.on_frame(t, seq, payload)
            seen = len(parser.frames)
            if seen > 1000:
                del parser.frames[:]
                seen = 0

    def on_frame(self, t, seq, payload):
        name = TYPE_NAMES.get(t, hex(t))
        try:
            f = proto.decode_payload(t, payload)
        except Exception as e:  # malformed payload for a known type
            print(f"\n[rx] {name} seq={seq} undecodable: {e}")
            return
        if self.show_all:
            print(f"\n[rx] {name} seq={seq} {f}")
        if name == "TELEMETRY":
            self.on_telemetry(f)
        elif name == "LOG":
            print(f"\n[log {['E', 'W', 'I', 'D'][f['level']] if f['level'] < 4 else f['level']}] {f['text']}")
        elif name == "ACK":
            status = {0: "OK", 1: "ERR", 2: "BUSY"}.get(f["status"], f["status"])
            print(f"\n[ack] {TYPE_NAMES.get(f['req_type'], f['req_type'])} seq={f['req_seq']} {status}")
        elif name == "HELLO_ACK":
            reason = RESET_REASONS.get(f["reset_reason"], f["reset_reason"])
            ok = "" if f["proto_ver"] == proto.PROTO_VER else f"  !! proto_ver mismatch (console {proto.PROTO_VER})"
            print(f"\n[hello] proto {f['proto_ver']} fw {f['fw_major']}.{f['fw_minor']} "
                  f"reset={reason} boots={f['reset_count']}{ok}")
        elif name == "PONG":
            sent = self.ping_sent.pop(f["ts"], None)
            if sent is not None:
                self.rtt_ms = (time.monotonic() - sent) * 1000
        elif name == "CONFIG_DATA":
            self.config = f
            print("\n[cfg] " + " ".join(f"{k}={fmt(v)}" for k, v in f.items()))

    def on_telemetry(self, f):
        prev, self.last_tel = self.last_tel, f
        fs = bool(f["flags"] & 0x01)
        if self.drop_at is not None and fs:
            print(f"\n[failsafe] after {(time.monotonic() - self.drop_at) * 1000:.0f} ms "
                  f"(±{1000 // 10} ms telemetry granularity)")
            self.drop_at = None
        if prev is not None and bool(prev["flags"] & 0x01) != fs and self.drop_at is None:
            print(f"\n[failsafe] {'ON' if fs else 'off'}")
        if self.show_tel:
            flags = [n for b, n in enumerate(["FS", "FA", "FB", "RAW", "OTA", "WIFI", "EN"]) if f["flags"] >> b & 1]
            rtt = f"{self.rtt_ms:.1f}" if self.rtt_ms is not None else "-"
            print(f"\n[tel] pwm={f['pwm']} vm={f['vm_mv']}mV rx={f['rx_frames']}/s crc_err={f['crc_err']} "
                  f"loop_max={f['loop_max_us']}us up={f['uptime_s']}s rtt={rtt}ms {','.join(flags)}")

    # --- commands ---------------------------------------------------------

    def handle(self, line):
        args = shlex.split(line)
        if not args:
            return
        c, rest = args[0].lower(), args[1:]
        moves = {"f": (0, 1, 0), "b": (0, -1, 0), "l": (-1, 0, 0), "r": (1, 0, 0), "cw": (0, 0, 1), "ccw": (0, 0, -1)}
        if c in ("help", "h", "?"):
            print(HELP.format(limit=self.limit))
        elif c == "d":
            self.cmd = tuple(clamp(float(v)) for v in (rest + ["0", "0", "0"])[:3])
            self.mode = "drive"
        elif c in moves:
            speed = float(rest[0]) if rest else 0.5
            self.cmd = tuple(clamp(v * speed) for v in moves[c])
            self.mode = "drive"
        elif c == "raw":
            self.raw = tuple(clamp(float(v)) for v in (rest + ["0"] * 4)[:4])
            self.mode = "raw"
        elif c == "idle":
            self.mode = "idle"
        elif c in ("stop", "s"):
            self.stop()
        elif c == "drop":
            self.mode = "silent"
            self.drop_at = time.monotonic()
            print("sending nothing; `idle` to resume")
        elif c == "limit":
            self.limit = max(0.0, min(1.0, float(rest[0])))
            print(f"limit = {self.limit}")
        elif c == "hello":
            self.send("HELLO", {"proto_ver": proto.PROTO_VER, "app_major": 0, "app_minor": 0})
        elif c == "ping":
            print(f"rtt = {self.rtt_ms:.1f} ms" if self.rtt_ms is not None else "no PONG yet")
        elif c == "cfg":
            self.send("GET_CONFIG")
        elif c == "set":
            self.set_config(rest)
        elif c == "tel":
            self.show_tel = (rest[0] == "on") if rest else not self.show_tel
        elif c == "raw-log":
            self.show_all = not self.show_all
        elif c == "reboot":
            self.mode = "idle"
            self.send("REBOOT")
        elif c in ("quit", "q", "exit"):
            raise EOFError
        else:
            print(f"unknown command {c!r}; `help`")

    def set_config(self, pairs):
        if self.config is None:
            print("no config yet: run `cfg` first")
            return
        new = dict(self.config)
        for p in pairs:
            k, _, v = p.partition("=")
            if k not in new:
                print(f"unknown field {k!r}; fields: {', '.join(new)}")
                return
            new[k] = [int(x) for x in v.split(",")] if isinstance(new[k], list) else int(v)
        self.send("CONFIG", new)
        self.send("GET_CONFIG")


def to_i8(v):
    return max(-127, min(127, round(v * 127)))


def clamp(v):
    return max(-1.0, min(1.0, v))


def fmt(v):
    return ",".join(map(str, v)) if isinstance(v, list) else str(v)


def find_port():
    for p in list_ports.comports():
        if p.vid == ESP_VID and p.pid == ESP_PID:
            return p.device
    sys.exit("ESP32-C6 USB Serial/JTAG (303a:1001) not found; pass --port")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", help="serial port (default: auto-detect 303a:1001)")
    args = ap.parse_args()

    con = Console(args.port or find_port())
    print(f"connected to {con.ser.port}; speed limit {con.limit}; `help` for commands")
    threading.Thread(target=con.reader, daemon=True).start()
    threading.Thread(target=con.heartbeat, daemon=True).start()
    con.send("HELLO", {"proto_ver": proto.PROTO_VER, "app_major": 0, "app_minor": 0})
    try:
        while con.running:
            try:
                con.handle(input("> "))
            except (ValueError, IndexError) as e:
                print(f"bad arguments: {e}")
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        if con.ser.is_open:
            con.stop()
            time.sleep(0.1)
        con.running = False
        print("\nstopped")


if __name__ == "__main__":
    main()
