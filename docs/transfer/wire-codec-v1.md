# Authenticated transfer wire codec v1

This offline codec is used only after full native TLS authentication and profile
verification. It does not authenticate the peer, enforce message ordering or
start any network operation. The future controller must do those checks.

Desktop-to-Watch parsing accepts READY (the exact v1 limits), STATUS, OFFER,
CANCEL (`user`/`locked`), PING/PONG and fixed-code ERROR. Watch-only responses,
obsolete COMMIT, unknown fields/types/codes and malformed identifiers are
rejected. Strict JSON retains the shared UTF-8/BOM/duplicate-key/depth/numeric
rules. OFFER is passed as its original raw bytes to the strict account parser:
its SHA256 binds spelling, escapes and whitespace rather than reserialization.
No correction/normalization is performed.

Watch output contains HELLO, UNKNOWN, CANCELLED, PING/PONG, REVIEW, RESULT or a
fixed ERROR. REVIEW accepts a planning candidate; RESULT accepts a confirmed
commit/retained-receipt result. Both serialize only transfer metadata and item
identifiers. Names, secrets, paths and exception text are never ERROR fields.
Only account-error codes may carry item_ids. The controller remains responsible
for refusing unconfirmed outcomes and validating READY session_id/ordering.

Framing uses an unsigned-on-wire four-byte big-endian length. Values outside
1..262144 (or a smaller configured preflight limit) fail before payload
allocation; signed-negative representations cover all values above Int.MAX.
Reads are exact and do not read ahead into another frame or TLS. Every partial
read uses the remaining monotonic deadline; completion after expiry also fails.
Truncation/zero progress fails without a busy loop. The caller owns and clears
the returned payload after parsing; the codec clears its own copied raw bytes.
JVM copies of strings cannot be guaranteed erased.

The socket owner must supply a real read timeout and a separate write watchdog
that closes stalled I/O. This helper's write method alone cannot bound a blocked
OutputStream. It does not replace session/review budgets, Wi-Fi binding,
reconnection serialization or lifecycle cleanup. No receiver permission/UI is
enabled by this change. Desktop must independently implement these exact wire
schemas and retain original OFFER bytes for STATUS/retry; no Desktop repository
or application is modified here.
