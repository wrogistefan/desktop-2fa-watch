# Transfer session owner adapter — 2026-11-13

Focused integration cases use synthetic in-memory storage through the actual
commit gate and controlled executors/latches. They exercise complete protocol
and session behavior without sockets/native contexts, including heartbeat during
blocked I/O, Lock, reconnect and STATUS, stale consent, local idle expiry,
output bounds, scope cancellation and resource-lease acknowledgement.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'org.desktop2fa.desktop.watch.presentation.VaultTransferOwnerTest' --tests 'org.desktop2fa.desktop.watch.presentation.TransferSessionTest' --stacktrace
```

Focused checks passed 32 cases (11 owner integration plus 21 existing transfer
session cases), zero failures/errors/skips. Full `python tools/interop/run.py --desktop-source ../../app/build/desktop-pinned`
verification for `a67e396` passed in 6m45s: 474 JVM tests, zero failures/errors,
one Windows symlink-capability skip, actual pinned synthetic Python
interoperability and debug APK. The subsequent reconnect refinement sends
HELLO/READY immediately and queues only STATUS behind the old writer. The
32-case focused suite passed again after this refinement (48s, zero failures,
errors or skips), including PING/PONG while the new channel's STATUS waits.
Final-head CI is required before merge.
Early tests exposed an incorrect valid-token check, which was fixed before PR.
A test that jumped over the 30-second network deadline was corrected to maintain
valid peer heartbeat before checking the separate 60-second inactivity cutoff.
Scope cancellation correctly includes VAULT_LOCKED before channel/session end.
No device, real network, native reconnect or Compose receiver acceptance is
claimed. See the [adapter contract](../transfer/session-owner-v1.md).
