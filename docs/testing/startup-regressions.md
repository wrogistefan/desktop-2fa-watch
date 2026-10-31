# Safe Startup Regression Checks (#19)

Scope: startup routing and create-only integration. The create-only writer and
error-reporting inspection were introduced in #38 (follow-up to closed #5).
This PR extracts the existing startup decision and storage-error component for
focused regression tests; it does not duplicate the writer or implement recovery.
All files, credentials and accounts used here are synthetic.

## Behavior and threat analysis

| Inspection / cached unlock | Result |
| --- | --- |
| Confirmed absence, no recovery candidate | Setup; cache is not consulted |
| Backup without main | Storage error/retry; cache is not consulted |
| Inspection failure or invalid path | Storage error/retry; never setup |
| Present file, no/unavailable cache | Manual unlock |
| Present file, wrong password, rejected data or read failure | Manual unlock; original unchanged |
| Present file, authenticated and validated cached load | Main with the returned vault |
| Cancellation during inspection/cache/load | Propagated; no navigation result |

Retry re-runs inspection; repeated errors never become evidence of absence.
Setup still uses `saveNew` and atomic `CREATE_NEW`, preventing replacement even
if another file appears after inspection. Updates remain a separate path.
No format/KDF migration is needed for supported v1 vaults. A partial first write
still blocks setup; `.bak` is not automatically promoted. This avoids interpreting
uncertainty as permission to destroy data, but is not full crash recovery or a
power-loss durability guarantee. Update-save faults are #20; recovery is #21.

`resolveVaultStartup` runs on MainActivity's I/O dispatcher. It returns constant
inspection messages, without raw cache/provider/path diagnostics. Cache/load
exceptions fall back to manual unlock; cancellation is rethrown. This narrow
change does not solve the later session-generation and lifecycle tasks #25/#26.

## Coverage and isolation

- `VaultStartupTest`: six JVM tests, including injected inspection/access errors,
  cache errors, password/format/I/O errors, successful load, no credential reads
  for absence/recovery, and cancellation at each boundary.
- Existing `VaultFilesTest` / `VaultTest`: competing creation, interrupted first
  write, backup-only refusal, successful create/load, wrong password, tamper and
  truncated data, with unchanged-byte assertions.
- `VaultStartupDeviceTest`: three Android tests use private disposable cache
  directories. They exercise fresh setup routing, actual encrypted create/load,
  manual/cached unlock, wrong password, refused replacement, backup-only and
  unsupported files. An isolated Compose host uses the production error/retry
  component and resolver to verify repeated errors and a successful retry.

The Android tests do not launch MainActivity, read the user's vault or invoke its
real credential cache. The retry host renders a route marker after success; it
does not repeat a full password-form/wizard UI pass. Historical full-app reports
remain separate. The existing encrypted-preference tests use prefixed stores.

## Validation

Local run: 2026-10-31 (Europe/Warsaw), on the read-only `Wear_OS_Large_Round`
AVD, Android 17 / API 37, explicitly selected as `emulator-5580`. It was started
with `-read-only -no-snapshot -no-window -no-audio` and does not save AVD changes.

| Command | Result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest --stacktrace` | Passed: 282 tests, zero failures/errors |
| `.\gradlew.bat :app:assembleDebug --stacktrace` | Passed |
| `.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | Passed; test APK rebuilt in this run |
| `git diff --check` | Passed |

JVM and emulator checks do not establish physical-watch behavior,
hardware-backed Keystore, full lifecycle,
IME, process death, secure memory erasure or filesystem power-loss guarantees.
