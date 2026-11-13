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

Full verification completed successfully with `python tools/interop/run.py
--desktop-source ../../app/build/desktop-pinned`: 407 JVM tests, zero failures or
errors, one Windows symlink-capability skip; actual pinned synthetic Python
interoperability and `:app:assembleDebug` passed. The run took 6m17s. Both Android
CI runs for the reviewed implementation also passed; Kilo reported no issues.
Sourcery review was unavailable because its weekly review budget was exhausted. The existing atomic writer,
Argon2id parameters and AES-GCM format are unchanged. Session/lifecycle/write-lock
integration is still required: this synchronous helper's caller must hold the
existing session mutex and own a non-cancellable mutation. No device, directory
power-loss, paired Desktop or full cancellation-protocol acceptance is claimed.
