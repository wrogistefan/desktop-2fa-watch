# Transfer session integration — 2026-11-13

The focused Wrapper run passed both `TransferSessionTest` (10 tests) and the
existing `VaultSessionTest` (18 tests); no failures/errors/skips. Controlled synthetic
storage/executor tests cover consent, receipt replay, v1 refusal, cancel during
preparation, old-owner rejection, Lock before/after publication, stale baseline
and renewed consent, pending-OFFER replacement rejection, manual-add exclusion
and disposal during publication. The real encrypted backend has separate
[publication tests](transfer-commit-2026-11-13.md).

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'org.desktop2fa.desktop.watch.presentation.TransferSessionTest' --tests 'org.desktop2fa.desktop.watch.presentation.VaultSessionTest' --stacktrace
```

Full `python tools/interop/run.py --desktop-source ../../app/build/desktop-pinned`
verification passed in 7m38s: 417 JVM tests, zero failures/errors, one Windows
symlink-capability skip, actual pinned synthetic Python interoperability and
`:app:assembleDebug`.
Device lifecycle, process death and paired Desktop acceptance are NOT RUN and
remain required at final acceptance. No receiver UI or app network permission
was added. See the [integration contract](../transfer/session-integration-v1.md).
