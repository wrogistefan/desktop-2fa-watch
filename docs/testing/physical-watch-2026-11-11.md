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
One full invocation does not satisfy the five-invocation protocol.

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
Three further full cooled invocations are still required.

An opt-in ManualObservationDeviceTest prepares a public synthetic fixture through
the real UI/session path for three minutes of human observation. Session and
screen clocks remain normal. Its runner result means host execution only;
ambient/recents/OEM observations require the owner's recorded answers. It skips
without d2faManualObservation=synthetic. Pending writes defer fixture cleanup.
