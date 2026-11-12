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
