# Transfer wire codec checks — 2026-11-13

Eleven new JVM cases passed with zero failures/errors/skips. They cover exact
limits/schemas/direction, obsolete COMMIT rejection, lexical errors and invalid
IDs, cancellation/heartbeats, fixed ERROR codes, metadata-only receipts, exact
OFFER digest and caller-buffer ownership, fragmented/no-read-ahead input,
invalid length before payload read, truncation/expiry and the maximum frame.
Existing `TransferOfferTest` was included in the focused Wrapper run.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'org.desktop2fa.desktop.watch.transfer.TransferWireTest' --tests 'org.desktop2fa.desktop.watch.transfer.TransferOfferTest' --stacktrace
```

Full `python tools/interop/run.py --desktop-source ../../app/build/desktop-pinned`
verification passed in 6m53s: 428 JVM tests, zero failures/errors, one Windows
symlink-capability skip, actual pinned synthetic Python interoperability and
`:app:assembleDebug`. Tests
use in-memory synthetic streams, not real sockets or TLS. State-machine, socket
write-watchdog, reconnect and paired-device acceptance are not claimed. Device
tests remain deferred to final acceptance. See the
[codec contract](../transfer/wire-codec-v1.md).
