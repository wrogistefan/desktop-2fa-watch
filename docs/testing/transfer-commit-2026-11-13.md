# Watch transfer publication backend — 2026-11-13

Owner-directed execution order now defers physical-watch/paired-Desktop tests
to the end. Automated tests continue. This change enables no receiver or real
account flow; it is the backend described in [the commit contract](../transfer/commit-boundary-v1.md).

Focused command (existing SDK selected via ANDROID_HOME):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'org.desktop2fa.desktop.watch.transfer.TransferCommitTest' --stacktrace
```

**16 tests passed, zero failures/errors/skips**. Real encrypted-file tests cover
accounts plus receipt, all-duplicate approval, replay without rewriting and wrong
password/source-byte preservation. Fault seams cover pre/post-boundary cancel,
stale snapshots, writer exceptions, unknown verification, recovery warnings,
missing main, orphan recovery files, partial/mutated writer data and no automatic
retry/reuse of an approval. Writer seams use memory-only synthetic snapshots;
they do not substitute for the real encrypted round-trip cases.

Full JVM/actual pinned synthetic Python interoperability/debug APK verification
is running and must be recorded after completion. The existing atomic writer,
Argon2id parameters and AES-GCM format are unchanged. Session/lifecycle/write-lock
integration is still required: this synchronous helper's caller must hold the
existing session mutex and own a non-cancellable mutation. No device, directory
power-loss, paired Desktop or full cancellation-protocol acceptance is claimed.
