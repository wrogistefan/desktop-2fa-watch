"""Synthetic frame/parser and real native loopback smoke regressions."""
import base64
import os
from pathlib import Path
import socket
import struct
import threading
import time
import sys
from unittest.mock import patch
import io
import unittest
import smoke_server as s


class ParserTests(unittest.TestCase):
    def test_strict_json(self):
        for data in [b'[]', b'{"v":1,"v":1}', b'{"v":1,"\\u0076":1}', b'{"v":1.0}',
                     b'{"v":1e0}', b'{"v":NaN}', b'\xef\xbb\xbf{}', b'{"a":"\xff"}',
                     b'{"a":' + b'[' * 8 + b'0' + b']' * 8 + b'}']:
            with self.subTest(data_kind=len(data)), self.assertRaises((s.SmokeFailure, UnicodeError)):
                s.parse(data)
        s.expect(s.parse(b'{"v":1,"type":"PING"}'), "PING", set())
        for version in [True, "1", 1.0]:
            with self.assertRaises(s.SmokeFailure):
                s.expect({"v":version, "type":"PING"}, "PING", set())

    def test_code_and_point(self):
        self.assertEqual(s.code_bytes("0000 0001"), b"00000001")
        for code in ["1234567", "123456789", "1234-5678", "1234567a", "１２３４５６７８"]:
            with self.assertRaises(s.SmokeFailure): s.code_bytes(code)
        good = base64.b64encode(bytes(32)).decode("ascii")
        self.assertEqual(s.point(good), bytes(32))
        for point in [good[:-1], good[:-2] + "B=", " " * 44]:
            with self.assertRaises(s.SmokeFailure): s.point(point)

    def test_frame_limit_before_payload(self):
        class Input:
            def __init__(self, data): self.data = bytearray(data)
            def settimeout(self, _): pass
            def recv(self, n):
                out = self.data[:min(n, 1)]; del self.data[:len(out)]; return bytes(out)
        for length in [0, 2049, 0xffffffff]:
            source = Input(struct.pack(">I", length) + b'x')
            with self.assertRaises(s.SmokeFailure): s.read_frame(source, time.monotonic() + 5)
            self.assertEqual(source.data, b'x')
        source = Input(s.encoded(s.message("PING"), 2048) + b'TLS')
        s.expect(s.read_frame(source, time.monotonic() + 5), "PING", set())
        self.assertEqual(source.data, b'TLS')
        with self.assertRaises(s.SmokeFailure): s.exact(source, 1, time.monotonic() - 1)


class HiddenInputTests(unittest.TestCase):
    def test_grouping_backspace_and_input_bounds(self):
        values = iter(bytes([b]) for b in b"0000 0002\x081\r")
        self.assertEqual(s.collect_code(lambda _: next(values), time.monotonic() + 5), b"00000001")
        for raw in [b"1234567\n", b"1234567890\n", b"a", b"\x00"]:
            values = iter(bytes([b]) for b in raw)
            with self.assertRaises(s.SmokeFailure):
                s.collect_code(lambda _: next(values), time.monotonic() + 5)
        with self.assertRaises(KeyboardInterrupt):
            s.collect_code(lambda _: b"\x03", time.monotonic() + 5)

    def test_deadline_is_checked_while_typing_without_enter(self):
        current = [0.0]
        def character(_):
            current[0] = 180.0
            return b"1"
        with patch.object(s.time, "monotonic", lambda: current[0]), self.assertRaises(s.SmokeFailure):
            s.collect_code(character, 180.0)

    def test_pending_prompt_rejects_closed_or_premature_peer_data(self):
        import select
        for closed in [True, False]:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
                listener.bind(("127.0.0.1", 0)); listener.listen(1)
                with socket.create_connection(listener.getsockname()) as client, listener.accept()[0] as server:
                    s.pending_input(time.monotonic() + 5, server)
                    if closed: client.shutdown(socket.SHUT_WR)
                    else: client.sendall(b"x")
                    self.assertTrue(select.select([server], [], [], 2)[0])
                    with self.assertRaises(s.SmokeFailure): s.pending_input(time.monotonic() + 5, server)

    @unittest.skipUnless(os.name == "nt", "Windows console adapter only")
    def test_windows_console_polling_has_no_echo_and_stops_on_timeout(self):
        import msvcrt
        import types
        output = io.StringIO()
        values = iter("00000001\r")
        with patch.object(sys, "stdin", types.SimpleNamespace(isatty=lambda: True)), \
             patch.object(sys, "stdout", output), \
             patch.object(s, "pending_input"), \
             patch.object(msvcrt, "kbhit", return_value=True), \
             patch.object(msvcrt, "getwch", side_effect=lambda: next(values)):
            self.assertEqual(s.read_terminal_code(time.monotonic() + 5, None), b"00000001")
        self.assertNotIn("00000001", output.getvalue())
        with patch.object(sys, "stdin", types.SimpleNamespace(isatty=lambda: True)), \
             patch.object(sys, "stdout", io.StringIO()), \
             patch.object(s, "pending_input", side_effect=s.SmokeFailure()), \
             patch.object(msvcrt, "kbhit", return_value=False):
            with self.assertRaises(s.SmokeFailure): s.read_terminal_code(time.monotonic() + 5, None)

    @unittest.skipIf(os.name == "nt", "POSIX terminal adapter only")
    def test_posix_pty_no_echo_and_terminal_restored_on_success_and_expiry(self):
        import pty
        import termios
        import tty
        import select
        import types
        for expire in [False, True]:
            master, slave = pty.openpty()
            previous = termios.tcgetattr(slave)
            entered = threading.Event()
            real_cbreak = tty.setcbreak
            def cbreak(*args):
                real_cbreak(*args); entered.set()
            def writer():
                if entered.wait(2) and not expire: os.write(master, b"00000001\n")
            worker = threading.Thread(target=writer, daemon=True); worker.start()
            output = io.StringIO()
            try:
                with patch.object(sys, "stdin", types.SimpleNamespace(isatty=lambda: True, fileno=lambda: slave)), \
                     patch.object(sys, "stdout", output), patch.object(tty, "setcbreak", side_effect=cbreak), \
                     patch.object(s, "pending_input", side_effect=s.SmokeFailure() if expire else None):
                    if expire:
                        with self.assertRaises(s.SmokeFailure): s.read_terminal_code(time.monotonic() + 5, None)
                    else:
                        self.assertEqual(s.read_terminal_code(time.monotonic() + 5, None), b"00000001")
                restored = termios.tcgetattr(slave)
                # Darwin sets PENDIN after a canonical-mode transition. It is
                # kernel pending-input state, not an ECHO/ICANON setting.
                # https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/termios.h
                if sys.platform == "darwin":
                    state_bit = termios.PENDIN
                    previous[3] &= ~state_bit
                    restored[3] &= ~state_bit
                self.assertEqual(previous, restored)
                self.assertEqual(previous[3] & (termios.ECHO | termios.ICANON),
                                 restored[3] & (termios.ECHO | termios.ICANON))
                self.assertFalse(select.select([master], [], [], 0)[0])  # No input echo in the PTY.
                self.assertNotIn("00000001", output.getvalue())
            finally:
                worker.join(timeout=3)
                os.close(master); os.close(slave)


@unittest.skipUnless(os.environ.get("D2FA_SMOKE_LIBRARY"), "Native library not provided")
class NativeLanTests(unittest.TestCase):
    def test_real_native_channel_and_wrong_code(self):
        # Loopback here verifies this harness only, never Watch Wi-Fi selection or timing.
        core = s.Core(Path(os.environ["D2FA_SMOKE_LIBRARY"]))
        for wrong in [False, True]:
            outcomes = []
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
                listener.bind(("127.0.0.1", 0)); listener.listen(1)
                def bob():
                    try:
                        connection, _ = listener.accept()
                        with connection:
                            s.serve(core, connection, lambda _: "00000002" if wrong else "00000001")
                        outcomes.append(True)
                    except Exception: outcomes.append(False)
                worker = threading.Thread(target=bob, daemon=True); worker.start()
                handle = 0
                authenticated = False
                try:
                    with socket.create_connection(listener.getsockname(), timeout=5) as sock:
                        wid = bytes(range(16))
                        s.send(sock, s.encoded(s.message("START", profile=s.PROFILE,
                               expected_sid=None, wid=wid.hex()), 2048), time.monotonic() + 5)
                        challenge = s.read_frame(sock, time.monotonic() + 5)
                        s.expect(challenge, "CHALLENGE", {"profile", "sid", "wid"})
                        sid = s.identifier(challenge["sid"])
                        handle = core.lib.d2fa_create(0, sid, 16, wid, 16, b"00000001", 8)
                        self.assertTrue(handle)
                        a = core.ffi.new("uint8_t[]", 32)
                        self.assertEqual(core.lib.d2fa_message(handle, a, 32), 32)
                        s.send(sock, s.encoded(s.message("PAKE_A", sid=sid.hex(), wid=wid.hex(),
                               message=base64.b64encode(bytes(core.ffi.buffer(a, 32))).decode("ascii")), 2048),
                               time.monotonic() + 5)
                        b = s.read_frame(sock, time.monotonic() + 5)
                        self.assertEqual(core.lib.d2fa_finish(handle, s.point(b["message"]), 32), 1)
                        pipe = s.TlsPipe(core, handle, sock)
                        pipe.handshake(time.monotonic() + 5)
                        authenticated = True
                        pipe.write(s.message("HELLO", session_id=sid.hex()), time.monotonic() + 5)
                        s.expect(pipe.read(time.monotonic() + 5), "READY", {"session_id", "max_accounts", "max_frame"})
                        pipe.write(s.message("PING"), time.monotonic() + 5)
                        s.expect(pipe.read(time.monotonic() + 5), "PONG", set())
                except Exception:
                    if not wrong: raise
                finally:
                    if handle: core.lib.d2fa_close(handle)
                    worker.join(timeout=10)
                self.assertFalse(worker.is_alive())
                self.assertEqual(authenticated, not wrong)
                self.assertEqual(outcomes, [not wrong])


if __name__ == "__main__": unittest.main()
