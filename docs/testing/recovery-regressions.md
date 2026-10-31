# Conservative Restart Recovery (#21)

Scope: restart inventory and authenticated **read-only recovery**, not writable
repair, backup export or automatic promotion. #20 preserves failed-write evidence;
this step lets the user regain access to codes without deciding which file to
destroy. All fixtures, passwords, directories and stage markers are synthetic.

## Decision table and threat analysis

| Files observed | Startup / recovery behavior |
| --- | --- |
| Confirmed absence of main and all known artifacts | Setup |
| Main only | Normal unlock; load errors cannot become setup |
| Backup only | Storage/recovery selection; never initialize over it |
| Fixed temp only | Storage/recovery selection; never initialize over it |
| Older random `.vault.*.tmp` only | Storage/recovery selection |
| Main with any backup/pending files | Storage/recovery selection, even if main is valid |
| Both valid old/new candidates | User chooses an authenticated read-only view; no freshness guess |
| Partial/corrupt/unsupported candidate | Reject opening it; preserve every file; another candidate may still open |
| Inspection failure, invalid path or excessive legacy inventory | Safe error/retry; never infer absence |

An inventory item is not proof of valid data. Opening re-inspects membership and
uses `Vault.load`, including bounded input, password authentication, versions,
unknown-field policy and account validation. Wrong passwords, tampering, truncation,
unsupported data, vanished candidates and I/O errors never modify disk or return
an empty replacement. Inventory admits at most 253 legacy candidates plus the
three fixed paths; directory streams close on all outcomes. Symlinks/non-regular
candidate paths are refused by the existing no-follow metadata check.

The selected file's source is visible in the code list, **Add Account** is disabled
and the submit handler independently rejects writes from recovery mode. No recovery
password is written to cache. Lock detaches the view immediately, clears input and
returns to storage/recovery selection. Existing cache cleanup runs independently.
The full late-completion/session/lifecycle guarantees remain #25/#26 work.

No file is promoted, replaced or deleted, including after a successful read.
An authenticated candidate does not prove that another valid candidate is obsolete.
This intentionally stops at code access: it is not permanent restore or writable
repair. Supported v1 files need no migration; all retained originals remain as-is.
Future transfer must refuse read-only recovery sessions, not use them as a write
target. Independent service recovery methods remain necessary.

## Tests and process isolation

- Six `VaultRecoveryTest` JVM tests cover eight file-state combinations, actual
  fixture authentication, interrupted old-style restore, password/tamper/truncation/
  unsupported errors, injected I/O failure, vanished/unlisted candidates, setup
  refusal for temp-only data and excessive/invalid inventory. Original byte
  assertions include main, backup and pending data.
- Existing startup/device expectations now require review when main and backup
  coexist. Previous create-only, replacement-fault, compatibility and crypto tests
  remain active.
- `RecoveryScreenDeviceTest` uses isolated Compose components: opening requires
  a password and an explicit choice, and the production Add Account action is
  disabled in recovery mode. It does not launch MainActivity/user storage.
- `VaultRecoveryRestartDeviceTest` prepares valid old/new encrypted fixtures in
  private disposable cache directories. A non-exported **debug-only** activity
  runs in a separate `:synthetic_recovery` process, builds a selected synthetic
  filesystem state and kills its own PID. A new child PID then inspects that state.
  Stages are before temp, after temp, after backup, after atomic commit and a
  partial historical restore. The controller verifies routing, authenticates the
  appropriate candidate, checks wrong-password failure and unchanged bytes.

The debug child only accepts a strictly named synthetic directory immediately
under app cache. Its Intents contain directory/stage identifiers, never credentials
or account data. Its marker contains only PID/stage, published after writing it.
It has no launcher/exported entry point and is outside `src/main`. No actual
MainActivity process is killed or launched by these tests. This is a real separate
Android process kill/relaunch around prepared persistence boundaries; it is not
a full-app UI relaunch or interruption inside the production writer syscall.

## Validation and limits

Local run: 2026-10-31, read-only `Wear_OS_Large_Round` AVD, Android 17 / API 37,
explicitly selected as `emulator-5580`, with
`-read-only -no-snapshot -no-window -no-audio` (no saved AVD changes).

| Command / inspection | Result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest --stacktrace` | Passed: 295 tests, zero failures/errors |
| `.\gradlew.bat :app:assembleDebug --stacktrace` | Passed |
| `.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | Passed: 10 tests, zero failures/errors; test APK rebuilt |
| `.\gradlew.bat :app:testDebugUnitTest :app:processReleaseMainManifest --stacktrace` | Passed; final JVM rerun after debug-helper nullability correction |
| Merged release main manifest | No synthetic activity/process or INTERNET permission; no release APK built/published |
| `git diff --check` | Passed |

The initial debug compile reported a nullable-parent warning in the synthetic
helper; it was corrected before the APK/device run and the final JVM rerun.
These checks do not prove
physical-watch power-loss durability, parent-directory persistence, hardware
Keystore behavior, lifecycle/IME correctness or secure memory erasure. Read-only
recovery makes no writable-repair, newest-copy, merge or cleanup guarantee.
