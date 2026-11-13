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

Focused verification passed 42 tests (19 protocol, 21 session, two real
encrypted STATUS cases), zero failures/errors/skips. Full JVM/pinned synthetic
interoperability/APK verification is pending. No socket, native reconnect, Compose receiver or device/process-death
acceptance is claimed. The event/effect machine still needs its serialized
session/transport adapter. See the [protocol contract](../transfer/protocol-state-v1.md).
