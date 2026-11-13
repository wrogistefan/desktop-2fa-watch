# Transfer protocol and read-only STATUS — 2026-11-13

Focused Wrapper verification uses only synthetic accounts/passwords, controlled
monotonic time and executor/latch scheduling. It covers session STATUS/restart,
unknown/error/conflicting digests, Lock/cancel invalidation, actual encrypted-file
receipt reads, wrong-password/mutated-data preservation and recovery artifacts.
Protocol cases cover ordering, local consent, duplicate OFFER, STATUS, replay,
cancel before/after the save gate, stale reapproval, disconnect/reconnect token
invalidation, heartbeat/idle boundaries and fixed review/session expiry.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'org.desktop2fa.desktop.watch.transfer.TransferProtocolTest' --tests 'org.desktop2fa.desktop.watch.presentation.TransferSessionTest' --tests 'org.desktop2fa.desktop.watch.presentation.TransferStatusStorageTest' --stacktrace
```

The initial implementation `fb9eac7` passed the full runner
`python tools/interop/run.py --desktop-source ../../app/build/desktop-pinned`
in 6m24s: 460 JVM tests, zero failures/errors, one Windows symlink-capability
skip, actual pinned synthetic Python interoperability and debug APK.

After review, focused verification passed 45 tests (21 protocol, 21 session,
three STATUS storage cases), zero failures/errors/skips. Added cases prove
malformed identifiers fail before file/KDF access, a 60-second save with live
heartbeats still emits RESULT, and a silent-peer timeout loses delivery without
claiming rollback. The final-head full suite is required in CI. No socket, native reconnect, Compose receiver or device/process-death
acceptance is claimed. The event/effect machine still needs its serialized
session/transport adapter. See the [protocol contract](../transfer/protocol-state-v1.md).
