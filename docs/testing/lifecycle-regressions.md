# Lifecycle and inactivity locking (#26)

Baseline: `c431286` (#51), branch `fix/26-lifecycle-inactivity-lock`.
All fixtures use public synthetic passwords/accounts and isolated app-cache
directories/preferences. No release, data format, KDF or shared security-standard
change is included.

## Behavior and threat review

Activity stop, screen off, device lock and 60 seconds without input invalidate
the session through its existing generation/cleanup path. Checks run before
accepting input, before publishing I/O, on lifecycle/focus/system events and at
the scheduled monotonic deadline. An old editor callback carries its original
generation and cannot restore text after Lock. Already-started writes keep the
existing commit/review behavior; timeout is not a rollback.

Pause/focus loss alone hides codes and cancels TOTP refresh, preserving an
unexpired session/draft for native editor interaction. Text edits and explicit
UI actions renew inactivity; ticks, I/O and passive focus/resume do not. Resume
samples current wall time in the first active composition. Time/time-zone
broadcasts refresh the sample without extending inactivity. There are no wake
locks, periodic background workers or new production permissions/services.

This follows Android's distinction between [paused and stopped activities](https://developer.android.com/guide/components/activities/activity-lifecycle).
The debug fixture exercises Android's actual [InputMethodService extract editor](https://developer.android.com/reference/android/inputmethodservice/InputMethodService),
including a provider which ignores the app's NO_FULLSCREEN/NO_EXTRACT_UI hints.
It does not emulate Samsung's implementation or prove every OEM avoids stopping
the Activity when opening an editor. That remains a physical-watch gate (#37).
Malicious keyboards/accessibility tools and a compromised OS remain outside the
trust boundary. Clearing references does not prove secure memory erasure;
screenshot/recent-app/ambient protection remains #27.

## Verification

JVM controlled-clock cases cover the exact 59,999/60,000 ms boundary, activity
renewal versus passive checks, stopped/locked timers, non-renewing resume and
fail-closed monotonic rollback. Session regressions cover stale UI tokens and
deadline invalidation immediately before read/write publication.

The full JVM suite completed with **325 tests, zero failures/errors** using:

```powershell
$env:GRADLE_USER_HOME='C:/Users/lukas/.gradle'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:processReleaseMainManifest --stacktrace
```

Android checks use the disposable Large Round API 37 emulator, serial
`emulator-5582`, without loading/saving snapshots. Scenarios cover exact timeout
and stale draft edits; pause versus stop; finite cleanup while the display stays
off; actual emulator PIN/keyguard locking; wall-clock change while paused; and
native extract-editor edits/Next/Done across more than 60 seconds of controlled
monotonic time. PIN setup refuses a device which is not an emulator or already
has credentials. Tests restore their IME, hardware-keyboard setting, accessibility
flags and synthetic PIN in finally blocks.

Initial harness runs failed on two fixture assumptions: Wear OS screen sleep
does not necessarily lock Keyguard, and UiAutomation command arguments do not
use shell quoting. The fixture now requests Android's actual lock-screen action,
validates IME selection and uses validated direct command tokens. Native edits
also need Compose frames; polling must advance the test clock. Failed attempts
are not counted as passing evidence.

The #25 [session report](session-regressions.md) separately records process-death
and late-commit checks; historical device reports do not establish current
physical-watch behavior. No physical KDF measurements, power-loss durability or
hardware-backed Keystore guarantee is claimed.
