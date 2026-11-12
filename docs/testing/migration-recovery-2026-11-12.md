# Watch migration-copy recovery — 2026-11-12

This is local recovery work in desktop-2fa-watch, not a Desktop app change or
transfer activation. The main app receives no network permission.

Focused test commands:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'org.desktop2fa.desktop.watch.vault.MigrationRecoveryTest' --tests 'org.desktop2fa.desktop.watch.presentation.VaultSessionTest' --tests 'org.desktop2fa.desktop.watch.presentation.VaultSessionStorageTest' --stacktrace
```

The first focused run passed with eight archive tests plus existing session/storage
regressions. The expanded run adds positive root absence, symlink refusal and
metadata-failure classification. Final full JVM/pinned synthetic Python/APK
results must be recorded after completion. No actual user vault is used.

New tests cover healthy main vs retained archive, missing main with empty/partial
migration directory, ordinary temp/backup precedence, wrong password, correct
v1 authenticated read-only view, corrupt/unsupported copy, stale/unlisted selection,
change during open, late archive before create, directory/file mismatch and
no mutation of any source bytes. Symlink creation may be unavailable on Windows;
record its skip explicitly and require the Linux CI case before merge.

The metadata inventory creates no directory/file. No archive is promoted,
restored, downgraded, deleted or merged. Current app session gates keep recovery
read-only and generation/lifecycle protections unchanged. UI adds an explicit
Review recovery files action after unlock errors. UI rendering, device/no-backup
behavior and lifecycle still need emulator/physical verification; JVM tests are
not those checks and no security audit is claimed.

A local expanded focused run overlapped a full run in the same worktree and
corrupted Gradle's binary result store (Buffer underflow). Neither overlapping
report is acceptance evidence. All vault inputs were synthetic fixtures. A clean,
serial full run or independent fresh CI is required; do not infer a vault/storage
failure from that reporting error.

## Clean serial verification

After both overlapping runs finished, `:app:cleanTestDebugUnitTest` passed, then
`python tools/interop/run.py --desktop-source ../../app/build/desktop-pinned`
passed alone: **378 JVM tests, zero failures/errors, one skipped** (Windows
symlink-creation privilege), actual pinned synthetic Python bridge and debug
APK included, 5m 3s. The symlink test now permits skipping only on Windows;
Linux CI must execute it or fail. The earlier overlapping runs remain invalid.

The debug synthetic context now overrides noBackupFilesDir inside its private
cache fixture and uses the same migration-aware storage constructor as the
production screen. It never inspects real app no-backup data. A prepared device
test exercises unlock error → explicit recovery selection → authenticated v1
read-only view → blocked addition → Lock, with unchanged source ciphertext.
It uses no secret-bearing Intents, screenshots or code-tree diagnostics. Test
APK compilation is separate from execution: no device test has been run yet.

The review suggestion to catch any Exception in the rejection helper is not
adopted: only VaultException counts as controlled rejection. An unexpected
runtime failure must fail a test rather than be mistaken for safe rejection.

## 2026-11-13 follow-up

The final `:app:assembleDebug :app:assembleDebugAndroidTest` build passed after
the synthetic no-backup isolation and containment-safe fixture cleanup edits.
The final focused MigrationRecoveryTest run passed **11 discovered, zero
failures/errors, one Windows-only symlink skip**. The prepared device test was
compiled, not run. Linux CI must execute the symlink case.

Final device-test review follow-up replaces implicit parent/selection failures
with a descriptive required-parent check and an explicit exactly-one assertion.
Cleanup preserves a primary test failure and attaches cleanup failures as
suppressed, while retaining every containment check. The synthetic absent-root
getter is intentional and documented; fixtures create the directory explicitly.
Linux symlink/permission failures are not skipped, because that CI case is
required evidence. The updated app/test APK build passed again (6 seconds).
Device execution remains NOT RUN.
