# Physical Watch validation — 2026-11-11

Status: in progress; does not close #27, #30, #37 or #13.
Source: 74dd6981834cea56b9199ab3a44c5cdda0c601ee, isolated worktree.
Samsung SM-L705F; Android 16/API 36; build BP2A.250325.020;
security patch 2026-07-05. Initial battery 91%, not charging, 32.1 C.
Owner confirmed the test device may receive debug installation; synthetic data only.
Local AGP upgrade in the main worktree and untracked .idea files were preserved.

Both APKs built with repository Wrapper:
`:app:assembleDebug :app:assembleDebugAndroidTest --stacktrace` (successful).
New worktree initially lacked SDK configuration; rerun used ANDROID_HOME.

Confirmed results:
- ScreenCaptureDeviceTest: 1/1 after wake, zero failures. Initial attempt failed
  the focused/drawn precondition and does not establish capture behavior.
- KeystoreLossDeviceTest: 2/2, complete runner result.
- ImmediateLockDeviceTest: 2/2 on retry; earlier connection lost before summary.
- VaultSessionDeviceTest recreationDropsTheActiveVaultAndSecretDraftWithoutChangingCommittedBytes:
  1/1 with complete on-device runner result.

Wireless ADB repeatedly lost foreground runner output. Incomplete invocations
are not passing evidence. One prematurely started invocation was canceled;
clean sequential runs supersede it. On-device /data/local/tmp reports retain
synthetic instrumentation output across host connection interruptions.
At that checkpoint the full session class was incomplete. Physical OEM keyboard, actual lock,
ambient, recents, accessibility and other required scenarios are not yet passed.
Keystore alias deletion checks do not establish hardware key invalidation;
packaged backup XML checks do not establish actual OS backup behavior.
Raw local reports: app/build/physical-results (ignored build artifacts).
Cleanup/restoration and any remaining test artifacts must be recorded at end.
## First physical KDF run

Opt-in physical mode, warmRounds=5, batchSize=32: passed 1/1 with 18 samples,
completed=true and cleanupDeferredForPendingIo=false. Raw secret-free metadata:
[run 1 JSON](physical-watch-2026-11-11-kdf-run1.json).
First-process unlock 13.315 s; warm unlock 11.723–11.840 s (N=5).
Manual save 34.846–35.130 s (N=6); batch commit 34.837–35.218 s (N=6).
Sampled process PSS reached 295826 KiB; largest sampled heartbeat gap 610 ms.
These are instrumented debug end-to-end measurements, not isolated Argon2,
true peak allocation, frame-jank statistics or a receiver performance budget.
End battery 80%, battery temperature 39.7 C. Cool before repeating.
This first-run checkpoint alone did not satisfy the five-invocation protocol; the completed five-run aggregate appears below.

## Session failures requiring investigation

The full on-device VaultSessionDeviceTest run recorded two failures:
`committedWriteAfterDisposalHandsReviewToRecreatedOwnersUntilExplicitRetry`
did not reach its synthetic I/O gate; `lateUnlockAndActivityRecreationNeverRestoreAnInvalidatedSession`
reported missing Compose hierarchies. A third case was interrupted by the
operator before completion. The Watch was repeatedly observed Dozing.
No causal conclusion or passing full-suite claim is made. A standalone
recreation case passed; this does not supersede the failures above.
Corrected reruns are recorded below; remaining physical scenarios and cleanup audit are still pending.
## Corrected debug-host run

Debug-only opt-in syntheticKeepScreenOn preserves foreground test conditions
for ownership/process cases. Lifecycle fixtures additionally wake before launch;
explicit screen-off/on commands remain exercised. No release/session/KDF change.
After rebuilding/installing: VaultSessionDeviceTest 4/4 passed;
VaultSessionProcessDeviceTest 1/1 passed; SessionLifecycleDeviceTest 5 passed,
1 emulator-only PIN case skipped, zero failures. Earlier failures remain above.
Together with capture/Keystore/ImmediateLock: 15 passed, 1 skipped.
The test fullscreen IME is not the physical Samsung/OEM keyboard assessment.
JVM and debug assembly passed after the initial host patch; final lifecycle-only
instrumentation update also built both APKs successfully.

## Second physical KDF run

Source b95e02aa579d271eb530efe300403b18e38ca48f (foreground debug-fixture
changes; benchmark workload unchanged). Start: battery 69%, 31.8 C, not charging;
MemAvailable 557424 KiB and /data available 19291968 KiB. End-condition snapshots
were taken during/cooling after the run, not at an exact synchronized endpoint.
Passed 1/1, 18 samples, completed=true, cleanupDeferredForPendingIo=false.
[Run 2 JSON](physical-watch-2026-11-11-kdf-run2.json).
First-process unlock 13.001 s; warm unlock 11.521–11.679 s (N=5);
manual save 34.530–34.992 s (N=6); batch commit 34.642–34.903 s (N=6).
At this checkpoint three further invocations remained; see the completed aggregate below.

An opt-in ManualObservationDeviceTest prepares a public synthetic fixture through
the real UI/session path for three minutes of human observation. Session and
screen clocks remain normal. Its runner result means host execution only;
ambient/recents/OEM observations require the owner's recorded answers. It skips
without d2faManualObservation=synthetic. Pending writes defer fixture cleanup.

## Manual observation driver

Only on the approved isolated Watch, after automated benchmarks finish:

```powershell
adb -s <test-serial> shell input keyevent 224
adb -s <test-serial> shell am instrument -r -w -e class org.desktop2fa.desktop.watch.presentation.ManualObservationDeviceTest -e d2faManualObservation synthetic org.desktop2fa.desktop.watch.test/androidx.test.runner.AndroidJUnitRunner
```

Wait for READY_SYNTHETIC_UI. The fixture contains public synthetic accounts and
remains available for three minutes. The normal 60-second inactivity policy
and real display/lifecycle clocks are active. Do not enter private credentials.
Record separately: OEM recents contents, natural ambient entry/exit with AOD
settings, actual owner-controlled test-device lock/unlock, and native Samsung
keyboard entry/Next/Done. A screen-off or expired session requires manual password
entry; do not label that expected closure as fixture failure. The observer must
report what was actually visible. A passing driver is not a visual pass.
No screenshots or personal settings are captured by this driver.

Cooling before run 3: battery temperature fell to 33.7 C and repeated readings
were stable; battery 63%. A provisional 33.5 C waiting threshold was relaxed by
0.2 C after the plateau. This is an experimental condition, not a battery safety
limit. Runs have differing battery/thermal conditions and test-only source SHAs;
benchmark workload and production cryptography remain unchanged.

## Third physical KDF run

Source 6fc2698f134c571e42cc87af80f4fa48a37a0727; start battery 63%, 33.7 C.
Passed 1/1; 18 samples; completed=true; cleanupDeferredForPendingIo=false.
[Run 3 JSON](physical-watch-2026-11-11-kdf-run3.json).
First-process unlock 13.679 s; warm unlock 11.834–11.992 s (N=5);
manual save 35.506–35.771 s (N=6); batch commit 35.392–35.649 s (N=6).
After collecting the completed report: battery 58%, temperature 36.0 C.
At this checkpoint two further invocations remained; see the completed aggregate below.

## Fourth physical KDF run

Source 6fc2698f134c571e42cc87af80f4fa48a37a0727; start battery 58%, 33.9 C.
Passed 1/1; 18 samples; completed=true; cleanupDeferredForPendingIo=false.
[Run 4 JSON](physical-watch-2026-11-11-kdf-run4.json).
First-process unlock 13.079 s; warm unlock 11.495–11.619 s (N=5);
manual save 34.483–34.754 s (N=6); batch commit 34.403–34.978 s (N=6).
After collecting the completed report: battery 53%, temperature 36.1 C.
At this checkpoint one further invocation remained; see the completed aggregate below.

The current commit path loads the latest file to compare the candidate, saves,
then reloads to verify the committed snapshot. Each step derives a key. Thus the
measured commit wait includes three KDF operations plus I/O/UI/sampling overhead;
it must not be presented as the latency of one encryption or one Argon2 call.

## Five-run KDF aggregate

All five invocations completed: 90 samples, zero failed operations,
cleanupDeferredForPendingIo=false in every report. First-process means a new
application process, not cold hardware/filesystem/boot. Run 5 source matches
run 3/4; start battery 52%, 33.7 C. [Run 5 JSON](physical-watch-2026-11-11-kdf-run5.json).

| Phase | N | Median ms | Range ms |
| --- | --- | --- | --- |
| batch-commit | 30 | 34986 | 34403–35649 |
| first-process-unlock | 5 | 13239 | 13001–13679 |
| manual-save | 30 | 35002.5 | 34483–35913 |
| warm-unlock | 25 | 11770 | 11495–12052 |

Maximum sampled PSS: 375198 KiB; maximum sampled main-thread heartbeat gap: 673 ms. These are instrumented debug process observations, not isolated KDF, guaranteed memory peaks or frame-jank measurements. Low-resource and native responsiveness/cancellation assessment remains incomplete. Receiver budgets remain deferred to the later owner model.

## Owner observation: AOD disabled

Owner confirmed AOD disabled. With the opt-in synthetic host ready, the owner
lowered the wrist/allowed natural display sleep. Corrected observation: screen
went dark and waking showed the Watch face. An initial report of the account
list returning was explicitly retracted and is not confirmed defect evidence.
OEM task preview, app reopening and AOD-enabled behavior remain separate checks.
After all measurements and during manual observation: battery 47%, 34.7 C.
Owner confirmed reopening D2FA from recent apps requested a password. The exact
preview appearance was not yet reported at this checkpoint; no preview pass is
inferred solely from the password prompt. Manual driver completed 1/1 with
READY_SYNTHETIC_UI and d2faManualCleanup=DONE.

Cleanup inspection found no synthetic_session/synthetic_recovery child processes.
Seven synthetic-restart cache directories from interrupted/earlier tests remain
retained for diagnosis; their contents were not opened or published. Do not claim
all artifacts removed. Normal completed benchmark invocations each reported no
deferred I/O. The original Samsung input method was previously confirmed restored.
