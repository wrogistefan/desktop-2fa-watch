"""Synthetic-only Python/CFFI Bob <-> Kotlin/JNI Alice, over private child pipes.

Not a configurator, not the v1 wire protocol, never feed real account data here.
Only test builds expose full K; raw values are compared in memory and never logged.
"""
import argparse
import os
from pathlib import Path
import secrets
import queue
import threading
import struct
import subprocess
import tempfile

from cffi import FFI


def require(condition):
    if not condition:
        raise RuntimeError("Native interoperability check failed (details redacted)")


class Peer:
    def __init__(self, java, jar, library, env):
        self.process = subprocess.Popen(
            [java, "-Djava.library.path=" + str(library.parent), "-jar", str(jar)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        self.responses = queue.Queue(maxsize=1)
        self.reader = threading.Thread(target=self._read_responses, daemon=True)
        self.reader.start()

    def _exact(self, length):
        out = bytearray()
        while len(out) < length:
            chunk = os.read(self.process.stdout.fileno(), length - len(out))
            require(chunk)
            out.extend(chunk)
        return bytes(out)

    def _read_responses(self):
        # Windows selectors do not support anonymous pipes. One bounded reader
        # thread keeps the same complete-response timeout on all three hosts.
        try:
            while True:
                length = struct.unpack(">I", self._exact(4))[0]
                require(length <= 16416)
                self.responses.put(self._exact(length))
        except Exception:
            try:
                self.responses.put_nowait(None)
            except queue.Full:
                pass

    def rpc(self, opcode, payload=b""):
        packet = bytes([opcode]) + payload
        self.process.stdin.write(struct.pack(">I", len(packet)) + packet)
        self.process.stdin.flush()
        result = self.responses.get(timeout=15)
        require(result is not None)
        return result

    def integer(self, opcode, handle, tail=b""):
        return struct.unpack(">i", self.rpc(opcode, struct.pack(">Q", handle) + tail))[0]

    def array(self, opcode, handle, tail=b""):
        result = self.rpc(opcode, struct.pack(">Q", handle) + tail)
        length = struct.unpack(">i", result[:4])[0]
        if length == -1:
            return None
        require(length == len(result) - 4)
        return result[4:]

    def close(self):
        try:
            self.process.stdin.close()
        except (BrokenPipeError, OSError):
            pass
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.reader.join(timeout=5)
        self.process.stdout.close()
        self.process.stderr.close()


def run(args):
    ffi = FFI()
    ffi.cdef("""
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
        int d2fa_disconnect(uint64_t);
        void d2fa_close(uint64_t);
        int d2fa_test_key(uint64_t, uint8_t *, size_t);
    """)
    library = args.library.resolve()
    buffer = ffi.new("uint8_t[]", 16384)
    key = ffi.new("uint8_t[]", 64)
    with tempfile.TemporaryDirectory(prefix="d2fa-synthetic-keylog-check-") as temp:
        keylog = Path(temp) / "must-not-exist.keys"
        env = os.environ.copy()
        env["SSLKEYLOGFILE"] = str(keylog)  # Check that the module ignores it.
        # BoringSSL does not read SSLKEYLOGFILE; no callback is installed by core.
        old_keylog = os.environ.get("SSLKEYLOGFILE")
        os.environ["SSLKEYLOGFILE"] = str(keylog)
        peer = None
        try:
            core = ffi.dlopen(str(library))
            peer = Peer(args.java, args.jar.resolve(), library, env)

            def drain_bob(bob, alice, fragment=16384):
                while True:
                    n = core.d2fa_tls_drain(bob, buffer, fragment)
                    if n < 0:
                        return False
                    if n == 0:
                        return True
                    if peer.integer(16, alice, bytes(ffi.buffer(buffer, n))) != n:
                        return False

            def drain_alice(alice, bob):
                data = peer.array(7, alice, struct.pack(">i", 16384))
                if data is None:
                    return False
                while data:
                    if core.d2fa_tls_feed(bob, data, len(data)) != len(data):
                        return False
                    data = peer.array(7, alice, struct.pack(">i", 16384))
                    if data is None:
                        return False
                return True

            def handshake(alice, bob, fragment=16384):
                if peer.integer(4, alice) != 1 or core.d2fa_tls_start(bob) != 1:
                    return False
                for _ in range(100):
                    a = peer.integer(5, alice)
                    if a < 0 or not drain_alice(alice, bob):
                        return False
                    b = core.d2fa_tls_step(bob)
                    if b < 0 or not drain_bob(bob, alice, fragment):
                        return False
                    if a == 1 and b == 1:
                        return True
                return False

            for iteration in range(args.count + 2):
                sid, wid = secrets.token_bytes(16), secrets.token_bytes(16)
                code = b"00001234" if iteration == 0 else bytes(secrets.choice(b"0123456789") for _ in range(8))
                wrong_code = iteration == args.count
                wrong_context = iteration == args.count + 1
                bob_code = bytes([(code[0] - 48 + 1) % 10 + 48]) + code[1:] if wrong_code else code
                bob_wid = bytes([wid[0] ^ 1]) + wid[1:] if wrong_context else wid
                alice = struct.unpack(">Q", peer.rpc(1, struct.pack(">i", 0) + sid + wid + code))[0]
                bob = core.d2fa_create(1, sid, 16, bob_wid, 16, bob_code, 8)
                require(alice and bob)
                try:
                    a_message = peer.array(2, alice)
                    require(a_message is not None and len(a_message) == 32)
                    require(core.d2fa_message(bob, buffer, 16384) == 32)
                    b_message = bytes(ffi.buffer(buffer, 32))
                    require(peer.integer(3, alice, b_message) == 1)
                    require(core.d2fa_finish(bob, a_message, 32) == 1)
                    require(core.d2fa_test_key(bob, key, 64) == 64)
                    a_key = peer.array(12, alice)
                    require(a_key is not None and len(a_key) == 64)
                    require(secrets.compare_digest(a_key, bytes(ffi.buffer(key, 64))) != (wrong_code or wrong_context))
                    ffi.memmove(key, b"\0" * 64, 64)
                    del a_key
                    authenticated = handshake(alice, bob, 1 if iteration == 0 else 16384)
                    if wrong_code or wrong_context:
                        require(not authenticated)
                        continue
                    require(authenticated)
                    marker = b"D2FA SYNTHETIC NONSECRET MARKER"
                    require(core.d2fa_tls_write(bob, marker, len(marker)) == len(marker))
                    require(drain_bob(bob, alice))
                    require(peer.array(9, alice, struct.pack(">i", 16384)) == marker)
                    if iteration == 0:
                        require(peer.integer(10, alice) == 1 and core.d2fa_disconnect(bob) == 1)
                        require(handshake(alice, bob))
                finally:
                    peer.rpc(11, struct.pack(">Q", alice))
                    core.d2fa_close(bob)
            require(not keylog.exists())
            print(f"PASS: {args.count} Python/CFFI <-> Kotlin/JNI exchanges; full K, TLS marker, reconnect, negatives; no key log")
        finally:
            if peer is not None:
                peer.close()
            ffi.memmove(key, b"\0" * 64, 64)
            if old_keylog is None:
                os.environ.pop("SSLKEYLOGFILE", None)
            else:
                os.environ["SSLKEYLOGFILE"] = old_keylog


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", required=True, type=Path)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--count", type=int, default=1000)
    options = parser.parse_args()
    require(1 <= options.count <= 1000)
    try:
        run(options)
    except Exception:
        raise SystemExit("Interop PoC failed (details redacted)") from None
