# Screen exposure assessment (#27 / C6)

Date: 2026-11-09. Status: **partial protection; hardware/ambient assessment open**.
No vault, KDF, session policy, permission or transport change. This does not close
#27, certify capture prevention or establish physical-device behavior.

## Exposed surfaces and bounded decision

| Surface | Potentially visible information |
| --- | --- |
| Setup/unlock/recovery password form | Password length/last-character behavior and error state; masking is not capture protection |
| Wizard, especially its visibility toggle | Service/account identity and the setup key |
| Main/read-only recovery cards | Account identity, live codes and timing |
| Storage/recovery review | File labels and non-secret operational state; preserve consistent protection during transitions |
| Native fullscreen IME | A different window/provider can display edited text; the app's window flag does not control it |

Apply `FLAG_SECURE` before composing **every** MainActivity screen, including
setup and locked states. Keeping the same rule through navigation avoids a
sensitive-screen transition gap. On API 33+ also request no task screenshots
with `setRecentsScreenshotEnabled(false)`; API 30–32 use the window flag alone.
This is an engineering choice to reduce accidental capture in an authenticator.
There is no capture-enable setting or release/debug production bypass.

Android documents secure-window restrictions for screenshots and insecure
displays, including limitations on older devices and overlays. This is a platform
request, not a defense against a compromised OS or physical observation.
[Android secure activities](https://developer.android.com/security/fraud-prevention/activities).

The API 33 task-preview request concerns Overview representations and does not
replace the secure-window flag. A theme background may represent the app instead.
OEM task-switcher behavior still needs actual observation.
[Activity reference](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)).

## Ambient is a separate exposure

Android's current Wear guidance distinguishes older blurred app snapshots from
Wear OS 6+ always-on foreground behavior for apps targeting API 36+. This app
targets API 37. Inference: absence of an explicit ambient integration is not
evidence that no app content can remain visible. Neither screenshot protection
nor a blurred display proves ambient redaction.
[Wear ambient guidance](https://developer.android.com/training/wearables/always-on).

Existing C4 invalidates on screen-off/device-lock/stop and hides codes on
pause/focus loss. It must not be described as having tested every ambient
transition. No ambient library, wake lock or background refresh is introduced
here. Before treating this control as a receiver gate, test native ambient
entry/exit on supported Wear versions, including visible wizard keys and the
system's last-frame treatment. If any content persists, scope explicit ambient
redaction/invalidation and its IME/lifecycle regressions before secret reception.

## Verification

The new `ScreenCaptureDeviceTest` uses only an opaque magenta synthetic surface
in the non-exported debug host. It takes compositor screenshots in memory,
checks that secure captures exclude the marker, briefly clears the flag only
on that disposable host to prove that capture can see the marker, restores it,
then checks protection after Activity recreation. No bitmap is saved or uploaded;
no password, key, account or code is drawn into the calibration surface.
This is a capture regression, not merely an assertion that a flag bit is set.

Initial capture execution: **1 case passed, zero failures/errors/skips** on the
owned disposable Large Round emulator (Android 17/API 37, 454 × 454). Source base
`9f5db4d` plus capture patch `de58ea1`; no personal vault is used. The same source
built debug and instrumentation APKs successfully with the repository Wrapper:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.desktop2fa.desktop.watch.presentation.ScreenCaptureDeviceTest' --no-configuration-cache --stacktrace
```

Integration verification on 2026-11-10 at `1543705`: the full interop runner completed successfully, verifying 22 artifact hashes and 23 corpus cases, all 343 JVM tests (zero failures/errors/skips), the actual pinned Python bridge and debug APK assembly. Gradle reused up-to-date task outputs on the confirming run. Capture and native fullscreen IME regressions each passed 1/1 after integration with the C5 focus correction; these remain emulator-only observations.
No test has yet established OEM recents, casting, secure displays, native IME
capture behavior, accessibility compatibility or physical ambient behavior.

## Capture workflow and remaining device matrix

Normal app screenshots, screen recording and mirroring may be blank or refused.
Existing README/report images predate protection and remain historical evidence.
Do not add a runtime bypass or disable protection on personal data to obtain
documentation images. Use synthetic design illustrations clearly labeled as such,
or a separately reviewed synthetic test-only capture workflow. Never publish a
physical photo containing real accounts, passwords, setup keys or codes.

The flag leaves Compose semantics and normal interaction available; it does not
disable accessibility services or establish an accessibility audit. Remote visual
assistance may lose the app image. The chosen keyboard/accessibility provider
remains trusted software with access to input; test its usability independently.

| Environment/scenario | Required evidence still missing |
| --- | --- |
| API 30 minimum and API 31–32 | Ordinary screenshot/recording and task-preview behavior; oldest supported path |
| API 33+ physical Wear | App screenshot, recents entry/exit, recreation and recovery screens |
| OEM/native fullscreen IME | Input/Next/Done usability and separate-window capture limits |
| Older/newer Wear ambient + AOD settings | Visible contents at entry, while dimmed, and after wake; no stale codes/keys |
| Screen off/resume | Existing password-only invalidation remains; no automatic reopening |
| Accessibility/remote support | Screen reader navigation, magnification and expected capture restrictions |

Use an explicitly isolated test Watch/profile and public synthetic fixtures.
Record unavailable scenarios rather than substituting emulator/JVM results.
