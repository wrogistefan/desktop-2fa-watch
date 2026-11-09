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
Device readiness uses [KeyguardManager.isDeviceLocked](https://developer.android.com/reference/android/app/KeyguardManager#isDeviceLocked()).
Review of the process fixture exposed that `isKeyguardLocked` also reports an
insecure/occluded lock-screen UI: the emulator reported showing=true and
occluded=true with deviceLocked=0, preventing ordinary startup with the wrong
API. The corrected check concerns authentication-required device lock; screen
off still invalidates independently and returning never supplies a vault password.
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

The merged release manifest was checked: no synthetic hosts/IME, BIND_INPUT_METHOD
or INTERNET entry. The test IME exists only in the debug source set.

Android command (same Wrapper/environment):

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.desktop2fa.desktop.watch.presentation.SessionLifecycleDeviceTest' --no-configuration-cache --stacktrace
```

The last six-case run passed five cases and failed only the PIN fixture's initial
unlock: enabling Keyguard before launching the session could lock the emulator
before the tested event. After moving PIN activation after the initial unlock,
the targeted `SessionLifecycleDeviceTest#secureEmulatorLockNeverAuthorizesCacheOrAResumedSession`
run passed **1 test, zero failures/errors/skips**. Thus every scenario has a
passing result; this is not a claim that one six-case invocation passed. The
native editor case in that five-pass run used injected character key events,
real extract-editor Next/Done and three controlled 61-second edit intervals.

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
validates IME selection and uses validated direct command tokens. Native
accessibility SET_TEXT only changed the extract view, so character events are
used instead. Active editor waits advance Compose frames; stopped-Activity waits
poll lifecycle/I/O independently. Advancing Compose frames for a deliberately
stopped Activity hung a harness run, which was explicitly terminated. An old
synthetic PIN left by an interrupted run was cleared only on the owned emulator
using its known public fixture PIN. Failed/aborted attempts are not passing evidence.

The process fixture now routes input through the production user-action guard,
uses a fixed monotonic clock to isolate process death from inactivity, and keeps
a synthetic foreground host for child launches. It can explicitly Retry startup
once after a launch transition invalidates it; Retry never authenticates. Error
markers contain only stage/screen metadata, never credentials or exception text.

The #25 [session report](session-regressions.md) separately records process-death
and late-commit checks; historical device reports do not establish current
physical-watch behavior. No physical KDF measurements, power-loss durability or
hardware-backed Keystore guarantee is claimed.

After the `isDeviceLocked` correction, `VaultSessionProcessDeviceTest` passed
**1/1**, with actual child termination/restart and unchanged encrypted bytes.
The full JVM suite passed again (**325/325**), debug assembly and release
manifest processing passed, and targeted secure-PIN/keyguard passed **1/1**.
The combined command used the tasks above plus `:app:connectedDebugAndroidTest`
and the `SessionLifecycleDeviceTest#secureEmulatorLockNeverAuthorizesCacheOrAResumedSession`
class filter. This is staged evidence, not a new single six-case pass.

GitHub CI passed for the initial implementation. Subsequent runs for the final
revision and its retry failed before tests due to external downloads: Gradle
distribution timeout (10 seconds) and Kotlin compiler HTTP 504. They are not
reported as passing checks or code/test failures. The equivalent full local
Wrapper checks above completed successfully; CI remains an infrastructure limit.
