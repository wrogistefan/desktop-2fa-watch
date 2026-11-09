# Credential cache outcomes and immediate Lock (#23)

Date: 2026-11-09. Baseline: `18b7804` (merged #48), branch
`test/23-cache-key-loss-and-lock`; app 0.2.0, no release/version change.
Only disposable synthetic vaults, preferences and public RFC keys are used.

## Behavior and threat review

Startup now distinguishes a readable cache with no password from unavailable
storage (including corrupt preferences/provider failures). Unavailability shows
a constant manual-password recovery warning, never setup or an empty replacement
vault. Password-bearing result objects redact their string representation.
Existing nullable `getPassword` callers retain their behavior; no public vault
API, encrypted v1 format, KDF or migration changes.

Lock still hides codes, detaches the active vault and clears inputs before
asynchronous deletion completes. Deletion needs no readable key and attempts all
three secure/legacy stores. A failed deletion warns that restart may still use
the cache; unlock remains disabled while cleanup is running. This task does not
claim to solve late-operation or lifecycle races (#25/#26).

The third historical credential store is now excluded from both backup-rule
formats, including cloud backup and device transfer. Packaged-resource tests
check every known store in all three policy groups. This checks configuration,
not actual OEM backup/restore behavior. Keystore remains separate from the
password-encrypted vault. Lost keys cannot recover remembered credentials;
manual vault-password unlock remains possible. Hardware protection and secure
memory erasure are not assumed. No network or shared AGENTS standard changes.

## Tests and isolation

- JVM: missing/unavailable/available reads, blank/corrupt storage, read errors,
  write commit failure, deletion false/exception for every store, continued
  cleanup, and no write/delete during a failed read.
- `KeystoreLossDeviceTest`: creates a UUID-named test key and prefixed encrypted
  preferences, deletes only that owned alias, verifies unavailable read/failed
  write, byte-identical vault and explicit-password loading, then clears the
  unreadable cache and verifies recovery. It also checks packaged backup rules.
- `ImmediateLockDeviceTest`: runs production MainActivity callbacks through a
  non-exported debug subclass with a validated synthetic cache directory and
  prefixed preferences. Corrupt preferences must show manual unlock without
  setup/reset. Delayed failed deletion must leave codes hidden and unlock
  disabled, followed by the cleanup warning and unchanged encrypted bytes.

All test-created files/preferences are cleaned in finally blocks. The key-loss
test never deletes the default or an existing user alias. The UI host passes
only a validated synthetic directory name through its Intent, never secrets.

## Verification

Local Wrapper, cached SDK 37, Gradle 9.6.0 and configured Java 25 daemon criteria.
`GRADLE_USER_HOME=C:/Users/lukas/.gradle` was set for the local environment;
the first sandbox attempt could not create a Wrapper lock under `C:/.gradle`.

| Command | Result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace` | Passed: 308 JVM tests, zero failures/errors; both APK builds passed |
| First `.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | No tests executed: existing emulator-5580 (Android 17 / API 37) rejected APK installation with system-service Broken pipe errors. Gradle reported success despite runner failure; this is not a pass. |
| `.\gradlew.bat :app:assembleDebugAndroidTest :app:processReleaseMainManifest --stacktrace` | Passed after the final test-diagnostic change; merged release manifest has no synthetic host or INTERNET permission |
| `git diff --check` | Passed |

A separate instance was attempted on port 5582 using
`-read-only -no-snapshot -no-window -no-audio`, then retried with software GPU
`-gpu swiftshader`. It stayed offline while startup diagnostics stopped at the
WHPX initialization boundary; no test cases ran there. The old port-5580 process
was also confirmed to be a disposable read-only/no-snapshot instance before
termination. No saved AVD data was intentionally changed. Emulator checks remain
**blocked by the environment**, not passed. The new instrumented suite must run
successfully before this PR is merged; do not infer a pass from Gradle's exit
code alone.

After stopping the old instance, starting port 5582 alone with software GPU
eventually brought Android online. The complete run then executed 18 tests:
17 passed, one failed while scrolling to Lock before the cleanup assertion.
All key-loss, backup-policy, corrupt-cache, storage and recovery cases passed.
An isolated diagnostic rerun of the Lock case passed (1/1); this alone does not
establish that the original gesture was reliable. The test now waits for actual
decrypted content (the title is also on the splash) and keeps scroll gestures
in the center of the round display, away from lower actions/system edges.

### Review follow-up

Late review of the #24 documentation raised three points. The third legacy
store's missing backup exclusions are addressed in this PR and checked against
packaged resources. The manual now explicitly states that failed cleanup leaves
deletion unconfirmed and older builds may reuse leftover credentials. The claim
that absent stores necessarily make `deleteSharedPreferences` return false is
not supported by the local Android 34 ContextImpl source (which returns true
when main and backup files are absent) or by the passing Android 17 cleanup
cases with absent legacy stores. No check-then-delete workaround or false-success
fallback is added; actual false/exception outcomes remain failures.

No physical watch, authentication-bound key invalidation, device-lock policy,
inactivity/background lock, IME or process-death session verification is claimed
here. Those integrated device scenarios remain #37; this report is not a security
audit or evidence of physical power-loss durability.
