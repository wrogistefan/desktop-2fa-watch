# Physical KDF measurement protocol (#30 / D3)

Status: **instrument prepared; physical measurements deferred to the final
roadmap phase by the owner on 2026-11-09**. No physical latency/memory result or
receiver-budget conclusion is claimed. #30 remains open.

## Workload and isolation

`presentation.KdfMeasurementDeviceTest` is opt-in instrumentation using the
production MainActivity/session/storage path and normal user-action guards.
No production Kotlin, KDF, salt/nonce, encryption, format or permission changes.
Only its debug host keeps the display interactive; the real 60-second session
clock remains active. This measures foreground latency, not ambient energy use.

The test APK contains an exact copy of public D1 `supported.d2fa` (13 records),
SHA-256 `a819a0f6db135b622ef0d09c78b7eee6affe7c569a31f4f1c5f52205327e4291`.
The corpus manifest pins its Desktop source. Hash verification/copying/initial
inspection performs no KDF before the first measured unlock. New accounts also
use public synthetic keys. Only validated synthetic cache directories/pref names
are used; no personal vault or unrelated Keystore alias is read/deleted.

The mode is explicit: `physical` refuses an emulator; `emulator-smoke` refuses a
physical Watch. Without a mode the test skips. Normal full-suite execution is
not an opt-in benchmark. Install only on an isolated test Watch/profile: these
app/test IDs match debug builds and may replace an existing installation.

## Final-device commands

Record model, OS/API/build/security patch, exact source, battery/charging,
thermal condition, free memory/storage and other workloads. Cool between runs;
keep conditions comparable and v1 KDF unchanged. Choose the test serial explicitly:

```powershell
$taskCommit = git rev-parse HEAD
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
adb -s <test-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <test-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <test-serial> shell am force-stop org.desktop2fa.desktop.watch
adb -s <test-serial> shell am instrument -w -e class org.desktop2fa.desktop.watch.presentation.KdfMeasurementDeviceTest -e d2faBenchmark physical -e sourceCommit $taskCommit -e warmRounds 5 -e batchSize 32 org.desktop2fa.desktop.watch.test/androidx.test.runner.AndroidJUnitRunner
```

Verify case/failure/skip counts and `completed`, not just adb exit status. The
source SHA is operator-supplied and must match the built APK; the APK cannot
independently verify the Git checkout. No personal profile is authorized here.

Round zero measures first-process unlock, first manual save after unlock, then
one batch commit. Five further rounds each explicitly lock/wait/unlock, manually
save one account and commit a batch: **18 samples** total. Warm rounds 1–20 and
batch size 1–32 are workload controls, not an approved future wire protocol.
Records grow within a run; each invocation starts with the same fresh fixture.
Repeat force-stop/instrument at least five times for five first-process samples.

First-process KDF is not cold boot, cleared filesystem cache or cold hardware.
Save requires prior unlock, so do not call first-save latency cold KDF latency:
report unlock/commit separately and combined user wait where useful. `am start
-W` does not measure password unlock. Never lower v1 KDF to improve the result.

## Measurements and limits

`d2faBenchmarkResult` instrumentation-status JSON contains source/fixture/device
metadata (no serial/owner identifiers), phase/round/count, elapsed ms, sampled
baseline/peak process PSS KiB, memory sample count, main-thread heartbeat count
and maximum gap, and final public screen/pending/active state. It contains no
password, key, account label, URI or code. No screenshot is taken or persisted.

Elapsed time covers guarded input, validation, KDF, I/O, verification, UI-owner
publication and sampling. Polling/PSS inspection adds overhead (about 100 ms
polling granularity): this is end-to-end debug measurement, not isolated Argon2.
PSS is sampled instrumented-process memory, not guaranteed peak allocation or
keyboard/system memory. The 100 ms heartbeat is not a frame/jank audit. A debug
storage hook rejects entry on the main thread; native interaction/profiling still
needs physical checks. Preserve all non-secret raw samples and report median,
range and exact N, separating first-process and warm results; avoid precise tail
claims from five/six samples. Emulator timings are not physical-watch evidence.

Stall, Lock, cancellation or uncertain publication is a recorded failure to
analyze, not a discarded slow sample or permission to resubmit. Normal cleanup
removes only the fixture/preferences. A still-pending writer leaves its synthetic
cache intact (`cleanupDeferredForPendingIo`), rather than deleting under a writer.
Preserve it for review. OOM/process death may prevent JSON entirely: report an
incomplete run and secret-free OS evidence, not a successful result.

## Final physical checklist (not performed)

| Scenario | Required evidence |
| --- | --- |
| First-process/warm unlock/manual/batch | Raw samples, N, median/range, exact source/workload/environment |
| Responsiveness/cancellation | Native interaction/profiler and Lock during I/O, actual saved/review result, no late reopen |
| Low memory/storage | Controlled isolated-device failure, retained old records and recovery; no personal vault stress |
| Lifecycle/IME/key behavior | Final #37 physical scenarios, separately from latency |
| Receiver budgets | Decide after actual hardware and the later communication model |
| Restoration | Confirm files/preferences/process cleanup or list owned artifacts still retained |

Harness smoke: **1 test passed, zero failures/errors/skips** on the Large Round Android 17/API 37 emulator, source `35e14ead2d44307a2709eb711a115011bd0d764e`, report timestamp 2026-11-10T01:48:30 UTC. This confirms smoke completion only; emulator timings are not physical measurements. Debug and test APK build passed in the preceding session. This table stays unfilled until
the final real-device phase; an emulator smoke cannot substitute for it.
