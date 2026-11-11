# Wear integration evidence and physical checklist (#37 / C5)

Later partial physical evidence: [SM-L705F report, 2026-11-11](physical-watch-2026-11-11.md). This dated report retains its original emulator-phase results and limitations.

Status: **partial evidence; physical-watch phase not performed, #37 stays open**.
Date: 2026-11-09. Base `9f5db4d` (#59), app development version 0.2.0.
Fixture changes `3f12712` and focus correction/regressions `61559b2` are on
`test/37-integrated-wear-evidence`. No format, KDF, networking or release
version is changed here.

## Environment and isolation

Owned disposable Large Round emulator, Android 17/API 37, 454 × 454, serial
`emulator-5582`, without snapshot load/save. No physical Watch is currently
available through ADB. No personal vault, password, key or service account is
used. Fixtures use validated synthetic cache directories and prefixed preferences;
Keystore loss deletes only the alias created by that specific synthetic test.
The default master alias/unrelated keys are never explicitly deleted.

The emulator-only PIN case refuses a non-emulator or a pre-existing credential.
It restores its known synthetic PIN/lock configuration. Native IME tests restore
the selected/enabled IME and hardware-keyboard setting. Finally blocks remove
fixture files/preferences and owned child processes. No TOTP value/password/key
or raw provider exception is copied into this report.

## Integrated emulator pass

Each class is invoked separately so counts can be checked independently; a
multi-class filter is not assumed to have executed every requested class.
The local driver first required that the only attached device was the owned
emulator. Exact per-class command from the root:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=<fully-qualified-class>' --no-configuration-cache --stacktrace
```

| Class | Cases | Current result |
| --- | --- | --- |
| `presentation.SessionLifecycleDeviceTest` | 6 | Passed, zero failures/errors/skips in one invocation |
| `vault.KeystoreLossDeviceTest` | 2 | Passed, zero failures/errors/skips |
| `presentation.ImmediateLockDeviceTest` | 2 | Passed, zero failures/errors/skips |
| `presentation.VaultSessionDeviceTest` | 4 | Passed, zero failures/errors/skips |
| `presentation.VaultSessionProcessDeviceTest` | 1 | Passed, zero failures/errors/skips |

Total: **15 cases, zero failures/errors/skips**. All five classes passed on the
focus-corrected runtime. The four-case ownership pass retained additional
failure-only diagnostic flags; those diagnostics were simplified before the
other classes ran. No test assertion or success path was removed.

All class names have prefix `org.desktop2fa.desktop.watch.`. XML/count summaries
are retained locally in `app/build/c5-results/` (ignored build artifacts). The
new full JVM/actual-Python/debug run for this change passed **343 tests, zero
failures/errors/skips**, including the actual pinned Python bridge. Exact command:

```powershell
$env:GRADLE_USER_HOME = 'C:/Users/lukas/.gradle'
python tools/interop/run.py --desktop-source app/build/desktop-pinned
```

The runner executed `:app:testDebugUnitTest :app:assembleDebug --stacktrace`
through the repository Wrapper and verified the bridge test was not skipped.
This is separate evidence, not an Android/Keystore result by itself. Relative
documentation links and `git diff --check` also passed.

The lifecycle pass exercises exact timeout/stale callbacks, pause versus stop,
screen-off cleanup, actual secure emulator lock, current wall time on resume
and real fullscreen extract-editor input/Next/Done across controlled deadlines.
PIN verification here combines authentication lock with screen sleep; it does
not establish every vendor's interactive/occluded lock transition behavior.

Keystore cases verify packaged credential backup exclusions and actual loss of
only an owned alias, unavailable cache reads/writes, preserved vault bytes and
manual password loading. They do not establish StrongBox/hardware backing or
permanent hardware-key invalidation under credential/biometric changes. Current
sessions do not use remembered credentials regardless of old preference state.

Immediate Lock cases verify corrupt legacy preferences cannot auto-open/reset
the vault and that delayed failed credential deletion cannot keep codes visible.
Remember is removed; tests cover present/absent/unreadable legacy data, not a
currently supported Remember toggle. Cache cleanup failure is not a data reset.

## Fixture corrections and honest limits

The first integrated lifecycle attempt passed four and failed two cases.
`isKeyguardLocked` was already true for an insecure occluded screen, so the
test could wake before actual lockNow/screen-off completed. It now waits for
authentication-required `isDeviceLocked` plus non-interactive state and vault
invalidation before waking. The production API already used isDeviceLocked.

Accessibility-window caching also made native editor lookup unreliable after
system-lock transitions. The debug-only IME now exposes its actual framework
extract view. Instrumentation waits for shown/enabled native `ExtractEditText`
and drives real character events and actual native Next/Done `View.performClick`.
This still traverses Android InputConnection/IME actions, not a mocked application
callback. It is not an accessibility audit. The view reference is cleared when
the test IME is destroyed, and no test IME/service appears in release sources.

Only the corrected six-case run is counted as passing. Prior failed attempts and
older Galaxy Watch Ultra reports are historical, not current physical evidence.
Process termination is not flash/power-loss durability; emulator timing is not a
physical KDF measurement. Screenshot/recent-app/ambient assessment remains #27.

Two initial four-case session runs each failed three cases. Ownership tests now
route modeled edits/navigation/retry through the production generation/input
guard and freeze only their test-host monotonic clock, separating delayed I/O
from the independently tested inactivity policy. Production time is unchanged.

Targeted diagnostics reproduced a real stale-focus state after Activity
recreation: lifecycle resumed, actual window focused, password-loaded vault
present, but cached focus false and codes hidden. The runtime now reconciles
actual focus in deadline/input/I/O-publication checks; the window-focus callback
remains authoritative during its transition. It neither renews inactivity nor
restores the old session. Diagnostics report booleans/screen names only; temporary
reflection used to locate the stale flag was removed from committed tests.

## Required physical phase (not performed)

Use a spare Watch or explicitly isolated test profile with **no personal vault**.
Do not install this debug app over a personal authenticator or clear/reset user
data to make a test pass. Record model, OS/API/build/security patch, app source
commit, test data version and sample counts; omit hardware serial/account/device
owner identifiers from public evidence. Use only public synthetic fixture data.

Build with the repository Wrapper. Install/run against the explicitly approved
test serial, rather than `connectedDebugAndroidTest` against every attached
device. On the test device, direct instrumentation preserves its synthetic data
between phases; do not assume a Gradle connected run preserves an installed app.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
adb -s <test-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <test-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <test-serial> shell am instrument -w -e class org.desktop2fa.desktop.watch.vault.KeystoreLossDeviceTest org.desktop2fa.desktop.watch.test/androidx.test.runner.AndroidJUnitRunner
```

Replace the placeholder only after confirming the isolated device/profile. Check
runner case/failure/skip counts, not merely the adb exit code. Automatic PIN
mutation is emulator-only; physical lock tests use the owner's existing **test**
device authentication manually, never replacing a personal credential.

| Physical scenario | Expected observations / evidence to record |
| --- | --- |
| Fresh startup and readable/absent/corrupt legacy stores | No cache auto-unlock or setup/reset; original file unchanged; cleanup warnings honest |
| Owned synthetic Keystore alias loss | Unavailable cache handled; original vault still loads manually; unrelated aliases untouched |
| Failed clear + Lock during delayed I/O | Codes/drafts disappear immediately, actual write result retained, no late reopen |
| Real native Samsung/OEM IME entry | Service/account/key edits and Back/Next/Done preserve an unexpired draft; final add once, no masked Stop regression |
| Watch face/other app, screen off, device lock, idle boundary | Session closes as specified; resume/wake requires password, no cached reopen |
| Paused/focus-lost UI and wall-clock change | Codes stop/hide while inactive and resume from current time without renewing inactivity |
| Activity recreation and actual owned-child termination | Draft/session absent afterward; committed synthetic data retained; manual review/unlock required |
| Before/after commit and lost certainty | No blind duplicate retry or false rollback; actual B4 review state and unchanged old records |
| Device/alias invalidation unavailable to reproduce | Mark not exercised with reason; never substitute emulator/JVM success |
| Backup/transfer and ambient/capture exposure | Distinguish packaged XML checks from actual OS backup/visual behavior; #27 remains separate |

Verify cleanup/restoration and preserve only secret-free result metadata. Stop
on any lost/changed unrelated data, unsupported device state or inability to
isolate the test profile. Do not weaken KDF/validation/session policy to obtain
a passing result. Physical latency/memory/responsiveness measurements are #30.

Until that phase and its actual report exist, this document cannot close #37 or
declare roadmap #13 ready for receiving real secrets. No audit is claimed.
