# Explicit transfer activation session checks — 2026-11-13

Focused Wrapper checks passed 16 `TransferSessionTest` cases (six activation
regressions plus ten transfer cases) and 18 existing `VaultSessionTest` cases,
with zero failures/errors/skips. No real vault or password was used.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'org.desktop2fa.desktop.watch.presentation.TransferSessionTest' --tests 'org.desktop2fa.desktop.watch.presentation.VaultSessionTest' --stacktrace
```

The new controlled synthetic cases cover no migration on unlock, exact review
identity, refusal and Lock invalidation, changed-source rejection, Lock during
started migration, queued work invalidation, private baseline and manual-write
exclusion. These test the session ownership contract; real encrypted archive,
wrong-password, mutation and publication-fault cases remain in
`VaultMigrationTest` and the full regression suite. They do not simulate power
loss or Android process death. Full JVM/interoperability/APK checks are pending.

There is no app entry point yet. Required confirmation wording and Desktop
consequences are in [the activation contract](../transfer/activation-v1.md).
Device and paired Desktop acceptance remain NOT RUN until the final stage.
