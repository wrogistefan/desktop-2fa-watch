# Shared manual/batch commit (#36)

Initial baseline: `c431286` (#51), rebased onto `ef3228b` (#52) before final checks;
branch `refactor/36-serialized-additions`.
Only public synthetic accounts, keys/passwords and temporary files are used.

## Scope and threat review

One immutable proposal captures the expected baseline and additions on the UI
thread. All account data is validated before mutation. The existing process-wide
session mutex serializes actual runtime writers and reads, across Activity
owners. Authenticated current storage must still match the expected data before
one whole-candidate save. A stale proposal is rejected for review, not silently
rebased. There is no receiving endpoint, new permission, global account ID,
receipt persistence, format/KDF change or multi-process coordination added.

The manual wizard retains standalone validation/collision behavior. Generic
in-memory additions do not resolve future receiver collisions or consent;
those remain #32/#33. Existing duplicates/null names/parameters are preserved.
The writer assumption and internal APIs are documented in
[development](../development.md#serialized-additions-36).

## Checks

- Candidate tests mutate input/output collections after capture, preserving the
  private proposal, historical duplicates/null names and supported parameters.
  Invalid first/later/baseline data, empty batches and unsupported versions are
  rejected before exposing a writable candidate.
- Session tests submit two competing batch/manual requests with controlled gates
  and executor barriers. The first commits the entire batch; the queued stale
  request cannot overwrite it. No sleeps determine submission order.
- Other session cases verify one commit call, invalid/failing batches, queued
  cancellation and Lock/cancellation after commit. Already-committed entries
  remain intact and an old operation cannot reopen its session.
- Real encrypted-file tests exercise a complete two-entry save and injected
  prepublication failure, requiring exactly one save, unchanged original bytes
  on failure and unchanged existing accounts/parameters on success. The existing
  B4 fault matrix remains responsible for detailed commit/uncertain outcomes.

## Executed results (2026-11-09)

Initial targeted candidate/session/storage checks passed **20/20**, with debug
assembly. After rebasing onto merged #26, the full JVM suite passed **333/333**;
debug and instrumentation APK assembly passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
```

On the owned Large Round API 37 emulator (`emulator-5582`), the unchanged native
extract-editor manual wizard ran through the new commit API: three fields,
Next/Done, inactivity renewal and one persisted addition preserving the original
entry. **1/1 passed**, without failures/errors/skips:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.desktop2fa.desktop.watch.presentation.SessionLifecycleDeviceTest#nativeFullscreenExtractEditorKeepsDraftAndDeliversNextAndDone' --no-configuration-cache --stacktrace
```

Commands ran from this branch's worktree with local Gradle cache/SDK configuration.
GitHub CI failed before test execution on the external Gradle distribution
download timeout, as also seen for #26/#28; it is not reported green. Local full
Wrapper checks above are the runtime evidence. JVM/emulator success does not
establish physical Keystore behavior, power-loss durability or transport security.
