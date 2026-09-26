#!/usr/bin/env python3
"""Generate protocol/vectors.json, the test vectors for PROTOCOL.md.

This is the reference implementation of the USB frame format: CRC-8, the
per-type payload layouts, header validation and the resync parser. Firmware
(firmware/test) and Android (core tests) must reproduce every vector here.

Usage: python3 protocol/gen_vectors.py        # writes protocol/vectors.json
       python3 protocol/gen_vectors.py --check # fails if the file is stale
"""

import hashlib
import json
import struct
import sys
from pathlib import Path

PROTO_VER = 1
SYNC = 0xAA
MAX_PAYLOAD = 200

# --- CRC-8: poly 0x07, init 0x00, no reflect, no xorout (CRC-8/SMBUS) -------


def crc8_bitwise(data: bytes) -> int:
    crc = 0x00
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


CRC8_TABLE = [crc8_bitwise(bytes([i])) for i in range(256)]


def crc8(data: bytes) -> int:
    crc = 0x00
    for b in data:
        crc = CRC8_TABLE[crc ^ b]
    assert crc == crc8_bitwise(data), "table and bitwise CRC disagree"
    return crc


assert crc8(b"123456789") == 0xF4, "CRC-8/SMBUS check value must be 0xF4"

# --- Payload layouts ---------------------------------------------------------
# Field kinds: u8 i8 u16 u32 (little-endian), u8x4 i8x4, hex32 (32 raw bytes),
# str8 (u8 length + UTF-8), rest_hex (remaining bytes), rest_text (remaining
# bytes as UTF-8, no terminator).

CONFIG_FIELDS = [
    ("version", "u8"), ("map", "u8x4"), ("invert", "u8x4"), ("max_duty", "u8"),
    ("slew_ms", "u16"), ("trim", "u8x4"), ("brake", "u8"), ("min_duty", "u8"),
    ("failsafe_ms", "u16"), ("pwm_hz", "u16"),
]

# type: (name, direction, fields, min_len, max_len)
TYPES = {
    0x00: ("HELLO", "phone_to_esp", [("proto_ver", "u8"), ("app_major", "u8"), ("app_minor", "u8")], 3, 3),
    0x01: ("DRIVE", "phone_to_esp", [("flags", "u8"), ("vx", "i8"), ("vy", "i8"), ("w", "i8")], 4, 4),
    0x02: ("MOTOR_RAW", "phone_to_esp", [("m", "i8x4")], 4, 4),
    0x03: ("PING", "phone_to_esp", [("ts", "u32")], 4, 4),
    0x04: ("CONFIG", "phone_to_esp", CONFIG_FIELDS, 22, 22),
    0x05: ("STOP", "phone_to_esp", [], 0, 0),
    0x06: ("GET_CONFIG", "phone_to_esp", [], 0, 0),
    0x10: ("OTA_BEGIN", "phone_to_esp", [("size", "u32"), ("sha256", "hex32")], 36, 36),
    0x11: ("OTA_DATA", "phone_to_esp", [("offset", "u32"), ("data", "rest_hex")], 5, 196),
    0x12: ("OTA_END", "phone_to_esp", [], 0, 0),
    0x13: ("OTA_ABORT", "phone_to_esp", [], 0, 0),
    0x20: ("WIFI_OTA_ENTER", "phone_to_esp", [("ssid", "str8"), ("pass", "str8")], 2, 97),
    0x21: ("WIFI_OTA_EXIT", "phone_to_esp", [], 0, 0),
    0x7F: ("REBOOT", "phone_to_esp", [], 0, 0),
    0x80: ("HELLO_ACK", "esp_to_phone",
           [("proto_ver", "u8"), ("fw_major", "u8"), ("fw_minor", "u8"),
            ("reset_reason", "u8"), ("reset_count", "u16")], 6, 6),
    0x81: ("TELEMETRY", "esp_to_phone",
           [("last_seq", "u8"), ("flags", "u8"), ("pwm", "i8x4"), ("vm_mv", "u16"),
            ("crc_err", "u16"), ("rx_frames", "u16"), ("uptime_s", "u32"),
            ("fw_major", "u8"), ("fw_minor", "u8"), ("loop_max_us", "u16")], 20, 20),
    0x82: ("ACK", "esp_to_phone", [("req_type", "u8"), ("req_seq", "u8"), ("status", "u8")], 3, 3),
    0x83: ("LOG", "esp_to_phone", [("level", "u8"), ("text", "rest_text")], 1, 200),
    0x84: ("PONG", "esp_to_phone", [("ts", "u32")], 4, 4),
    0x85: ("CONFIG_DATA", "esp_to_phone", CONFIG_FIELDS, 22, 22),
    0x86: ("WIFI_STATUS", "esp_to_phone", [("state", "u8"), ("ip", "u8x4")], 5, 5),
}
NAME_TO_TYPE = {v[0]: k for k, v in TYPES.items()}

for _t, (_n, _d, _f, _lo, _hi) in TYPES.items():
    assert 0 <= _lo <= _hi <= MAX_PAYLOAD, _n


def encode_payload(type_: int, fields: dict) -> bytes:
    out = bytearray()
    for name, kind in TYPES[type_][2]:
        v = fields[name]
        if kind == "u8":
            out += struct.pack("<B", v)
        elif kind == "i8":
            out += struct.pack("<b", v)
        elif kind == "u16":
            out += struct.pack("<H", v)
        elif kind == "u32":
            out += struct.pack("<I", v)
        elif kind == "u8x4":
            out += struct.pack("<4B", *v)
        elif kind == "i8x4":
            out += struct.pack("<4b", *v)
        elif kind == "hex32":
            raw = bytes.fromhex(v)
            assert len(raw) == 32
            out += raw
        elif kind == "str8":
            raw = v.encode("utf-8")
            out += bytes([len(raw)]) + raw
        elif kind == "rest_hex":
            out += bytes.fromhex(v)
        elif kind == "rest_text":
            out += v.encode("utf-8")
        else:
            raise ValueError(kind)
    return bytes(out)


def decode_payload(type_: int, payload: bytes) -> dict:
    fields, i = {}, 0
    for name, kind in TYPES[type_][2]:
        if kind in ("u8", "i8"):
            fields[name] = struct.unpack_from("<B" if kind == "u8" else "<b", payload, i)[0]
            i += 1
        elif kind == "u16":
            fields[name] = struct.unpack_from("<H", payload, i)[0]
            i += 2
        elif kind == "u32":
            fields[name] = struct.unpack_from("<I", payload, i)[0]
            i += 4
        elif kind in ("u8x4", "i8x4"):
            fields[name] = list(struct.unpack_from("<4B" if kind == "u8x4" else "<4b", payload, i))
            i += 4
        elif kind == "hex32":
            fields[name] = payload[i:i + 32].hex()
            i += 32
        elif kind == "str8":
            n = payload[i]
            fields[name] = payload[i + 1:i + 1 + n].decode("utf-8")
            i += 1 + n
        elif kind == "rest_hex":
            fields[name] = payload[i:].hex()
            i = len(payload)
        elif kind == "rest_text":
            fields[name] = payload[i:].decode("utf-8")
            i = len(payload)
    assert i == len(payload), f"{TYPES[type_][0]}: {len(payload) - i} trailing bytes"
    return fields


def encode_frame(type_: int, seq: int, payload: bytes) -> bytes:
    lo, hi = TYPES[type_][3], TYPES[type_][4]
    assert lo <= len(payload) <= hi, f"{TYPES[type_][0]}: len {len(payload)} not in {lo}..{hi}"
    body = bytes([type_, len(payload), seq]) + payload
    return bytes([SYNC]) + body + bytes([crc8(body)])


def raw_frame(type_: int, seq: int, payload: bytes, crc=None) -> bytes:
    """Frame without validation, for building malformed stream vectors."""
    body = bytes([type_, len(payload), seq]) + payload
    return bytes([SYNC]) + body + bytes([crc8(body) if crc is None else crc])


# --- Reference parser (PROTOCOL.md §"Приём") ---------------------------------


class Parser:
    def __init__(self):
        self.buf = bytearray()
        self.frames = []
        self.crc_err = 0

    def feed(self, data: bytes):
        self.buf += data
        while True:
            i = self.buf.find(SYNC)
            if i < 0:
                self.buf.clear()
                return
            del self.buf[:i]
            if len(self.buf) < 2:
                return
            t = self.buf[1]
            if t not in TYPES:
                self._reject()
                continue
            if len(self.buf) < 3:
                return
            n = self.buf[2]
            if n > MAX_PAYLOAD or not TYPES[t][3] <= n <= TYPES[t][4]:
                self._reject()
                continue
            total = 5 + n
            if len(self.buf) < total:
                return
            if crc8(bytes(self.buf[1:4 + n])) != self.buf[4 + n]:
                self._reject()
                continue
            self.frames.append((t, self.buf[3], bytes(self.buf[4:4 + n])))
            del self.buf[:total]

    def _reject(self):
        # Drop only the sync byte and rescan: a real frame may start inside
        # what looked like the rejected one.
        self.crc_err += 1
        del self.buf[0]


# --- Vectors -----------------------------------------------------------------

DEFAULT_CONFIG = {
    "version": 1, "map": [0, 1, 2, 3], "invert": [0, 0, 0, 0], "max_duty": 100,
    "slew_ms": 250, "trim": [100, 100, 100, 100], "brake": 1, "min_duty": 15,
    "failsafe_ms": 300, "pwm_hz": 20000,
}

FW_BIN = bytes(range(256)) * 4  # stand-in firmware image for OTA_BEGIN
DRIVE_REMOTE_ENABLE = 0x01 | (2 << 1)

# (name, type_name, seq, fields, note)
FRAME_CASES = [
    ("hello", "HELLO", 0, {"proto_ver": PROTO_VER, "app_major": 0, "app_minor": 1}, None),
    ("drive_forward_half", "DRIVE", 1, {"flags": 0x01, "vx": 0, "vy": 64, "w": 0},
     "enable, source=test, vy=+0.5 вперёд"),
    ("drive_heartbeat", "DRIVE", 2, {"flags": 0x00, "vx": 0, "vy": 0, "w": 0},
     "enable=0: «пульс» без движения, сбрасывает failsafe"),
    ("drive_remote_full_negative", "DRIVE", 3,
     {"flags": DRIVE_REMOTE_ENABLE, "vx": -127, "vy": -127, "w": -127},
     "enable, source=remote; −127 кодируется как 0x81"),
    ("drive_local_pad_turn", "DRIVE", 4, {"flags": 0x01 | (1 << 1), "vx": 0, "vy": 0, "w": 127},
     "enable, source=local pad, w=+1 разворот по часовой"),
    ("drive_minus_128_clamped", "DRIVE", 5, {"flags": 0x01, "vx": -128, "vy": 0, "w": 0},
     "−128 вне диапазона: приёмник трактует как −127"),
    ("motor_raw", "MOTOR_RAW", 6, {"m": [127, -127, 64, 0]}, None),
    ("ping", "PING", 7, {"ts": 1}, None),
    ("ping_large_ts", "PING", 8, {"ts": 0xDEADBEEF}, "проверка little-endian"),
    ("config_defaults", "CONFIG", 9, DEFAULT_CONFIG, "значения по умолчанию §3.4"),
    ("config_calibrated", "CONFIG", 10,
     {**DEFAULT_CONFIG, "map": [1, 0, 3, 2], "invert": [0, 1, 0, 1], "max_duty": 80,
      "trim": [100, 95, 100, 90], "brake": 0, "min_duty": 20}, None),
    ("stop", "STOP", 11, {}, None),
    ("get_config", "GET_CONFIG", 12, {}, None),
    ("ota_begin", "OTA_BEGIN", 13, {"size": len(FW_BIN), "sha256": hashlib.sha256(FW_BIN).hexdigest()},
     "size и sha256 для образа bytes(range(256))*4"),
    ("ota_data_first", "OTA_DATA", 14, {"offset": 0, "data": FW_BIN[:192].hex()}, "полный кусок 192 байта"),
    ("ota_data_last", "OTA_DATA", 15, {"offset": 960, "data": FW_BIN[960:].hex()}, "последний кусок 64 байта"),
    ("ota_end", "OTA_END", 16, {}, None),
    ("ota_abort", "OTA_ABORT", 17, {}, None),
    ("wifi_ota_enter", "WIFI_OTA_ENTER", 18, {"ssid": "home-net", "pass": "secret123"}, None),
    ("wifi_ota_enter_saved", "WIFI_OTA_ENTER", 19, {"ssid": "", "pass": ""},
     "пустые строки: использовать сохранённые в NVS"),
    ("wifi_ota_exit", "WIFI_OTA_EXIT", 20, {}, None),
    ("reboot", "REBOOT", 21, {}, None),
    ("hello_ack", "HELLO_ACK", 0,
     {"proto_ver": PROTO_VER, "fw_major": 0, "fw_minor": 1, "reset_reason": 1, "reset_count": 3},
     "reset_reason=1 (ESP_RST_POWERON)"),
    ("telemetry", "TELEMETRY", 1,
     {"last_seq": 4, "flags": 0x40, "pwm": [40, -40, 40, -40], "vm_mv": 3580, "crc_err": 2,
      "rx_frames": 40, "uptime_s": 3600, "fw_major": 0, "fw_minor": 1, "loop_max_us": 850},
     "flags: bit6 enable"),
    ("telemetry_failsafe_fault", "TELEMETRY", 2,
     {"last_seq": 255, "flags": 0x01 | 0x02 | 0x08, "pwm": [0, 0, 0, 0], "vm_mv": 3100, "crc_err": 0,
      "rx_frames": 0, "uptime_s": 70000, "fw_major": 0, "fw_minor": 1, "loop_max_us": 65535},
     "flags: failsafe + fault A + raw mode"),
    ("ack_ok", "ACK", 3, {"req_type": 0x04, "req_seq": 9, "status": 0}, "ACK на config_defaults"),
    ("ack_busy", "ACK", 4, {"req_type": 0x10, "req_seq": 13, "status": 2}, None),
    ("log_info", "LOG", 5, {"level": 2, "text": "boot ok"}, None),
    ("log_utf8", "LOG", 6, {"level": 1, "text": "VM низкое"}, "текст UTF-8, без терминатора"),
    ("pong", "PONG", 7, {"ts": 1}, "ответ на ping"),
    ("config_data", "CONFIG_DATA", 8, DEFAULT_CONFIG, None),
    ("wifi_status_connected", "WIFI_STATUS", 9, {"state": 2, "ip": [192, 168, 1, 42]}, None),
]


def frame_vectors():
    out = []
    for name, type_name, seq, fields, note in FRAME_CASES:
        t = NAME_TO_TYPE[type_name]
        payload = encode_payload(t, fields)
        assert decode_payload(t, payload) == fields, name
        frame = encode_frame(t, seq, payload)
        p = Parser()
        p.feed(frame)
        assert p.frames == [(t, seq, payload)] and p.crc_err == 0, name
        v = {
            "name": name, "type": t, "type_name": type_name, "direction": TYPES[t][1],
            "seq": seq, "fields": fields, "payload_hex": payload.hex(), "frame_hex": frame.hex(),
        }
        if note:
            v["note"] = note
        out.append(v)
    # every type has at least one vector
    assert {v["type"] for v in out} == set(TYPES), "missing frame vector for some type"
    return out


def drive(seq, vx=0, vy=0, w=0, flags=0x01):
    return encode_frame(0x01, seq, encode_payload(0x01, {"flags": flags, "vx": vx, "vy": vy, "w": w}))


def ping(seq, ts):
    return encode_frame(0x03, seq, encode_payload(0x03, {"ts": ts}))


def stream_vectors():
    d1, d2 = drive(1, vy=64), drive(2, vy=64)
    good_log = encode_frame(0x83, 0, encode_payload(0x83, {"level": 2, "text": "x"}))
    tele = next(c for c in FRAME_CASES if c[0] == "telemetry")
    tele_frame = encode_frame(0x81, 1, encode_payload(0x81, tele[3]))
    cases = [
        ("back_to_back", "два кадра подряд", [ping(1, 1) + d2], 2, 0),
        ("split_chunks", "кадры порезаны на куски посреди заголовка и payload",
         [d1[:2], d1[2:6], d1[6:] + d2[:1], d2[1:]], 2, 0),
        ("byte_by_byte", "по одному байту", [bytes([b]) for b in d1], 1, 0),
        ("leading_garbage", "мусор без 0xAA перед кадром", [bytes.fromhex("00ff1337") + d1], 1, 0),
        ("garbage_sync_short", "мусорный 0xAA, за которым сразу настоящий кадр: "
         "его 0xAA читается как len, заголовок отклоняется, сканирование с байта после первого 0xAA",
         [bytes.fromhex("aa01") + d1], 1, 1),
        ("unknown_type", "кадр с неизвестным type и верной CRC отклоняется на заголовке",
         [raw_frame(0x55, 0, b"") + d1], 1, 1),
        ("wrong_len_for_type", "DRIVE с len=5 (верная CRC) отклоняется на заголовке",
         [raw_frame(0x01, 0, bytes(5)) + d1], 1, 1),
        ("len_over_200", "LOG с len=201 отклоняется на заголовке",
         [raw_frame(0x83, 0, bytes(201)) + good_log], 1, 1),
        ("bad_crc_then_valid", "испорченная CRC, затем верный кадр",
         [raw_frame(0x01, 1, d1[4:8], crc=d1[-1] ^ 0xFF) + d2], 1, 1),
        ("corrupted_payload", "бит в payload перевёрнут, CRC не сходится",
         [d1[:5] + bytes([d1[5] ^ 0x01]) + d1[6:] + d2], 1, 1),
        ("truncated_then_valid", "обрыв кадра посередине, затем верный кадр",
         [d1[:5] + d2], 1, 1),
        ("truncated_telemetry_then_valid", "обрыв длинного кадра: приёмник ждёт хвост, "
         "получает чужие байты, CRC не сходится, пересканирует буфер",
         [tele_frame[:10] + tele_frame + tele_frame], 2, 1),
        ("payload_contains_sync", "0xAA внутри payload не ломает приём",
         [drive(3, vx=-86) + ping(4, 0xAAAAAAAA)], 2, 0),
        ("seq_wrap", "seq переходит 0xFF → 0x00",
         [drive(0xFE) + drive(0xFF) + drive(0x00) + drive(0x01)], 4, 0),
        ("empty_payload", "кадр без payload (STOP ×3)",
         [encode_frame(0x05, s, b"") for s in (10, 11, 12)], 3, 0),
        ("trailing_partial", "в конце потока неполный кадр: не выдаётся и не считается ошибкой",
         [d1 + d2[:6]], 1, 0),
    ]
    out = []
    for name, desc, chunks, n_frames, n_err in cases:
        p = Parser()
        for c in chunks:
            p.feed(c)
        assert len(p.frames) == n_frames, f"{name}: got {len(p.frames)} frames"
        assert p.crc_err == n_err, f"{name}: got crc_err {p.crc_err}"
        out.append({
            "name": name, "description": desc, "chunks": [c.hex() for c in chunks],
            "expected_frames": [{"type": t, "seq": s, "payload_hex": pl.hex()} for t, s, pl in p.frames],
            "expected_crc_err": p.crc_err,
        })
    return out


def crc_vectors():
    cases = [
        ("check_string", b"123456789"),
        ("empty", b""),
        ("single_00", b"\x00"),
        ("single_ff", b"\xff"),
        ("single_aa", b"\xaa"),
        ("header_stop", bytes([0x05, 0x00, 0x00])),
        ("ramp_203", bytes([0x83, 200, 0x00]) + bytes(range(200))),
    ]
    return [{"name": n, "data_hex": d.hex(), "crc": crc8(d)} for n, d in cases]


def build():
    return {
        "_comment": "Generated by protocol/gen_vectors.py — do not edit by hand. Spec: protocol/PROTOCOL.md.",
        "proto_ver": PROTO_VER,
        "crc8": {"poly": 0x07, "init": 0x00, "reflect": False, "xorout": 0x00, "vectors": crc_vectors()},
        "types": [
            {"type": t, "name": n, "direction": d, "min_len": lo, "max_len": hi}
            for t, (n, d, _f, lo, hi) in sorted(TYPES.items())
        ],
        "frames": frame_vectors(),
        "streams": stream_vectors(),
    }


def main():
    path = Path(__file__).with_name("vectors.json")
    text = json.dumps(build(), ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        if not path.exists() or path.read_text(encoding="utf-8") != text:
            sys.exit(f"{path} is stale: run python3 protocol/gen_vectors.py")
        print(f"{path} is up to date")
        return
    path.write_text(text, encoding="utf-8")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
