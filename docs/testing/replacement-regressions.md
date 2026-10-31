# Vault Replacement Regression Checks (#20)

Scope: in-process existing-vault publication and filesystem fault handling.
All fixtures, passwords, entries and disposable paths are synthetic. Create-only
initialization remains separate (#38/#19). No format/KDF, network or release change.

## Decision and threat analysis

The previous catch-all fallback attempted a non-atomic move after any atomic
move failure, then copied `.bak` over the destination on failure. Android's
[Files.move contract](https://developer.android.com/reference/java/nio/file/Files#move(java.nio.file.Path,%20java.nio.file.Path,%20java.nio.file.CopyOption...))
does not promise a complete destination after a non-atomic failure. A blind
restore can also destroy a newer result when the operation's outcome is uncertain.

This change removes both the non-atomic fallback and automatic restore. No error
justifies that fallback in this implementation; `AtomicMoveNotSupportedException`
fails safely too. The tradeoff is deliberate: storage without atomic replacement
cannot update a vault. No fallback/restore fault hook remains because neither
operation exists; tests verify a single failed move and preservation instead.

Updates write an encrypted `vault.d2fa.tmp` using `CREATE_NEW`, flush/sync/close,
copy the old main to `.bak` without replacement, sync that backup, then request
atomic replacement. Both fixed artifact paths are excluded from Android backup
and device transfer. Old random `.vault.*.tmp` artifacts are not rewritten by this
path; their inspection/recovery remains #21.

| Outcome | File handling |
| --- | --- |
| Missing main or invalid path | Refuse update; never initialize |
| Temp creation/write/flush/sync/close fails | Old main retained; any temp retained |
| Backup copy/sync fails | Old main retained; temp and any backup retained |
| Existing temp/backup | Never overwrite that recovery candidate |
| Atomic move unsupported/fails | No fallback/restore; retain all remaining evidence |
| Move replaced main but reports an exception | Keep new main and old backup; report uncertainty |
| Move returns successfully | Commit point; main is new, temp was moved |
| Backup cleanup fails after commit | Keep new main; internal committed-with-artifacts result |

The public `save` API still returns Unit and does not yet expose the internal
cleanup status. UI treatment of uncertain/post-commit errors is #22, including
the existing generic retry gap; this PR does not claim that gap is fixed. Retained
candidates can block another update until conservative recovery (#21). No automatic
artifact deletion or recovery is introduced here, and no full multi-operation
serialization claim is made (#36).

## Regression coverage

`VaultReplacementTest` uses the immutable Python-written synthetic fixture as
the old encrypted bytes and a separately encrypted valid v1 candidate as new
bytes. The new candidate is loaded in the success test; existing fixture tests
validate the old fixture. Seven tests cover the table, including an eight-stage
precommit fault matrix, partial writes/backups, exact byte preservation, output
closure, a single unsupported move, a reported failure after a real move, cleanup
failure and refusal to overwrite pre-existing artifacts. No raw fault diagnostics
are returned in the helper's public error message.

`VaultReplacementDeviceTest` creates an encrypted disposable vault on Android,
loads/adds/saves/loads it through production APIs, checks all fields and cleanup,
then injects atomic-move unavailability and verifies preserved main/backup/temp.
It never opens MainActivity, the real vault or credential cache. Only the private
test directory is removed afterwards. Existing wrong-password, tamper, truncation,
validation, version, bounded-read and create-only suites remain active.

## Verification and limits

Local run: 2026-10-31, read-only `Wear_OS_Large_Round` AVD, Android 17 / API 37,
explicitly selected as `emulator-5580`. It runs with
`-read-only -no-snapshot -no-window -no-audio`, without saving AVD changes.

| Command | Result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest --stacktrace` | Passed: 289 tests, zero failures/errors |
| `.\gradlew.bat :app:assembleDebug --stacktrace` | Passed |
| `.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | Passed: 8 tests, zero failures/errors; test APK rebuilt |
| `git diff --check` | Passed |

Emulator/filesystem and injected JVM checks cannot prove physical power-loss durability. File `force`
does not establish parent-directory durability on every filesystem/device.
There is no physical-watch power-cut, process-kill/relaunch or automatic recovery
evidence in this task; restart tests are #21 and UI reconciliation is #22.
