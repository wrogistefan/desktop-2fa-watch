"""Isolated synthetic LAN smoke peer. No vault access, account payloads or key logs.

Code is read from the terminal, never a command argument/environment variable.
This is deliberately not the Desktop configurator or a complete v0.3 receiver.
"""
import argparse
import base64
import getpass
import ipaddress
import json
import re
import secrets
import socket
import struct
import sys
import warnings
import time
from pathlib import Path
from cffi import FFI

PROFILE = "D2FA-SPAKE2-BSSL1"


class SmokeFailure(Exception):
    def __init__(self):
        super().__init__("Transport test failed (details redacted)")


def require(condition):
    if not condition:
        raise SmokeFailure()


def remaining(deadline):
    result = deadline - time.monotonic()
    require(result > 0)
    return result


def exact(sock, count, deadline):
    result = bytearray()
    while len(result) < count:
        sock.settimeout(remaining(deadline))
        data = sock.recv(count - len(result))
        require(data)
        result.extend(data)
    remaining(deadline)
    return bytes(result)


def parse(data):
    def pairs(items):
        out = {}
        for key, value in items:
            require(key not in out)
            out[key] = value
        return out

    def reject(_):
        raise SmokeFailure()

    # Bound nesting before the library parser (which otherwise recurses first).
    depth, quoted, escaped = 0, False, False
    text = data.decode("utf-8", errors="strict")
    require(not text.startswith("\ufeff"))
    for character in text:
        if quoted:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                quoted = False
        elif character == '"':
            quoted = True
        elif character in "[{":
            depth += 1
            require(depth <= 8)
        elif character in "]}":
            depth -= 1
            require(depth >= 0)
    try:
        value = json.loads(text, object_pairs_hook=pairs, parse_float=reject,
                           parse_constant=reject)
    except (ValueError, RecursionError):
        raise SmokeFailure() from None
    require(type(value) is dict)
    return value


def message(kind, **fields):
    return {"v": 1, "type": kind, **fields}


def expect(value, kind, fields):
    require(set(value) == {"v", "type", *fields})
    require(type(value["v"]) is int and value["v"] == 1 and value["type"] == kind)


def identifier(value):
    require(type(value) is str and re.fullmatch("[0-9a-f]{32}", value))
    return bytes.fromhex(value)


def point(value):
    require(type(value) is str and len(value) == 44)
    try:
        data = base64.b64decode(value, validate=True)
    except ValueError:
        raise SmokeFailure() from None
    require(len(data) == 32 and base64.b64encode(data).decode("ascii") == value)
    return data


def code_bytes(value):
    if len(value) == 9 and value[4] == " ":
        value = value[:4] + value[5:]
    require(re.fullmatch("[0-9]{8}", value) is not None)
    return value.encode("ascii")


def read_frame(sock, deadline, limit=2048):
    size = struct.unpack(">I", exact(sock, 4, deadline))[0]
    require(1 <= size <= limit)
    return parse(exact(sock, size, deadline))


def encoded(value, limit):
    data = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    require(1 <= len(data) <= limit)
    return struct.pack(">I", len(data)) + data


def send(sock, data, deadline):
    sock.settimeout(remaining(deadline))
    sock.sendall(data)
    remaining(deadline)


class Core:
    def __init__(self, path):
        self.ffi = FFI()
        self.ffi.cdef("""
            uint64_t d2fa_create(int, const uint8_t *, size_t, const uint8_t *, size_t,
                                const uint8_t *, size_t);
            int d2fa_message(uint64_t, uint8_t *, size_t);
            int d2fa_finish(uint64_t, const uint8_t *, size_t);
            int d2fa_tls_start(uint64_t);
            int d2fa_tls_step(uint64_t);
            int d2fa_tls_feed(uint64_t, const uint8_t *, size_t);
            int d2fa_tls_drain(uint64_t, uint8_t *, size_t);
            int d2fa_tls_write(uint64_t, const uint8_t *, size_t);
            int d2fa_tls_read(uint64_t, uint8_t *, size_t);
            void d2fa_close(uint64_t);
        """)
        self.lib = self.ffi.dlopen(str(path.resolve()))


class TlsPipe:
    def __init__(self, core, handle, sock):
        self.core, self.handle, self.sock = core, handle, sock
        self.buffer = core.ffi.new("uint8_t[]", 16384)

    def drain(self, deadline):
        while True:
            count = self.core.lib.d2fa_tls_drain(self.handle, self.buffer, 16384)
            require(count >= 0)
            if not count:
                return
            send(self.sock, bytes(self.core.ffi.buffer(self.buffer, count)), deadline)

    def feed(self, deadline):
        self.sock.settimeout(remaining(deadline))
        data = self.sock.recv(16384)
        require(data and self.core.lib.d2fa_tls_feed(self.handle, data, len(data)) == len(data))
        remaining(deadline)

    def handshake(self, deadline):
        require(self.core.lib.d2fa_tls_start(self.handle) == 1)
        while True:
            remaining(deadline)
            result = self.core.lib.d2fa_tls_step(self.handle)
            require(result >= 0)
            self.drain(deadline)
            if result == 1:
                remaining(deadline)
                return
            self.feed(deadline)

    def exact(self, size, deadline):
        out = bytearray()
        while len(out) < size:
            remaining(deadline)
            count = self.core.lib.d2fa_tls_read(self.handle, self.buffer, min(16384, size - len(out)))
            require(count >= 0)
            self.drain(deadline)
            if count:
                out.extend(self.core.ffi.buffer(self.buffer, count))
            else:
                self.feed(deadline)
        remaining(deadline)
        return bytes(out)

    def read(self, deadline):
        size = struct.unpack(">I", self.exact(4, deadline))[0]
        require(1 <= size <= 262144)
        return parse(self.exact(size, deadline))

    def write(self, value, deadline):
        packet = encoded(value, 262144)
        offset = 0
        while offset < len(packet):
            remaining(deadline)
            chunk = packet[offset:offset + 16384]
            count = self.core.lib.d2fa_tls_write(self.handle, chunk, len(chunk))
            require(0 < count <= len(chunk))  # Memory BIO: fail closed, no unsafe retry.
            offset += count
            self.drain(deadline)


def serve(core, connection, read_code):
    handle = 0
    try:
        start = read_frame(connection, time.monotonic() + 5)
        expect(start, "START", {"profile", "expected_sid", "wid"})
        require(start["profile"] == PROFILE and start["expected_sid"] is None)
        wid = identifier(start["wid"])
        sid = secrets.token_bytes(16)
        send(connection, encoded(message("CHALLENGE", profile=PROFILE, sid=sid.hex(), wid=wid.hex()), 2048),
             time.monotonic() + 5)
        a = read_frame(connection, time.monotonic() + 5)
        expect(a, "PAKE_A", {"sid", "wid", "message"})
        require(identifier(a["sid"]) == sid and identifier(a["wid"]) == wid)
        a_bytes = point(a["message"])
        expiry = time.monotonic() + 180
        password = code_bytes(read_code())
        remaining(expiry)  # Terminal input itself is blocking; see documented PoC limitation.
        deadline = min(expiry, time.monotonic() + 15)
        handle = core.lib.d2fa_create(1, sid, 16, wid, 16, password, 8)
        password = None
        require(handle != 0)
        b = core.ffi.new("uint8_t[]", 32)
        require(core.lib.d2fa_message(handle, b, 32) == 32)
        require(core.lib.d2fa_finish(handle, a_bytes, 32) == 1)
        send(connection, encoded(message("PAKE_B", sid=sid.hex(), wid=wid.hex(),
             message=base64.b64encode(bytes(core.ffi.buffer(b, 32))).decode("ascii")), 2048), deadline)
        pipe = TlsPipe(core, handle, connection)
        pipe.handshake(deadline)
        hello = pipe.read(time.monotonic() + 15)
        expect(hello, "HELLO", {"session_id"})
        require(identifier(hello["session_id"]) == sid)
        pipe.write(message("READY", session_id=sid.hex(), max_accounts=50, max_frame=262144), time.monotonic() + 15)
        expect(pipe.read(time.monotonic() + 15), "PING", set())
        pipe.write(message("PONG"), time.monotonic() + 15)
    finally:
        if handle:
            core.lib.d2fa_close(handle)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--bind", required=True, help="Explicit LAN IPv4 interface, never 0.0.0.0")
    args = parser.parse_args()
    try:
        require(sys.stdin.isatty())
        warnings.simplefilter("error", getpass.GetPassWarning)
        address = ipaddress.IPv4Address(args.bind)
        require(not (address.is_unspecified or address.is_multicast or address.is_loopback))
        core = Core(args.library)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind((str(address), 0))
            listener.listen(1)
            listener.settimeout(60)
            print("Synthetic test server:", str(address) + ":" + str(listener.getsockname()[1]), flush=True)
            print("Enter this address on the separate Watch test app. No accounts are accepted.", flush=True)
            connection, _ = listener.accept()
            with connection:
                serve(core, connection, lambda: getpass.getpass("Code shown on the watch: "))
        print("PASS: authenticated synthetic channel; no accounts transferred")
    except (Exception, KeyboardInterrupt):
        print("FAIL: test stopped or could not authenticate (details redacted)")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
