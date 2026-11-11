# Physical Watch validation — 2026-11-11

Status: partial physical evidence; automated/manual runs described below are finished. Remaining limits do not close #27, #30, #37 or #13.
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

## Owner observations: preview and AOD enabled

Owner confirmed recent-app card was blank/obscured with no accounts or codes.
Opening the app requested a password. With AOD enabled temporarily, natural
wrist-down/dimmed display showed the Watch face without app data. This observation
covers the account-list fixture on this SM-L705F/API 36, not all OEMs/versions or
visible setup-key/IME surfaces. AOD was originally off and must be restored.
AOD-on manual driver subsequently completed 1/1 with cleanup DONE after waking;
its sleep wait had extended across device sleep. Manual drivers now support an
explicit validated synthetic control-directory finish marker and elapsed-time
waits. No release behavior changed.

The first wizard attempt became unavailable before the owner could enter text;
it is not keyboard evidence. Its driver was ended explicitly and cleanup DONE
was confirmed. The second attempt initially displayed storage unavailable after
background/launch transitions. Owner pressed Retry storage check once, then the
synthetic wizard became ready. No reset/recovery replacement was selected.
The keyboard-only fixture keeps its screen on; session timeout remains normal.
Owner confirmed Samsung keyboard Next from Service name reached Account name.

Owner confirmed Samsung keyboard Next also reached Setup key, Show/Hide worked,
and one Done action saved the synthetic account and returned to the list.
A brief red storage-related message was reported approximately, not verbatim.
Source inspection identifies a likely explanation: operationNotice becomes
STALLED_OPERATION after 30 seconds, and measured commits take about 35 seconds;
the wizard renders that notice in pale red. This is an inference, not an exact
identification of the observed text. No failed-save claim follows from it.
The native save completed; no retry was requested.

Owner confirmed a real existing device credential lock was exercised and D2FA
requested its password afterward. Pre-lock active/expired state is being clarified;
without it, do not claim isolation from the separate 60-second inactivity timeout.
No device PIN or pattern was supplied to tools or changed.

The first real credential-lock attempt began with the app already asking for its
password, so it did not isolate device lock from inactivity. A fresh lock-check
fixture was then prepared: owner confirmed codes were visible immediately before
locking the Watch, then D2FA required its password after owner-authenticated unlock.
The lock-check fixture kept its display on to avoid unplanned natural sleep,
without changing the real session clock or existing device credential. Its driver
completed 1/1 with cleanup DONE via explicit finish marker.

## Visible setup-key observation

The initial key-surface driver failed its readiness deadline and reported cleanup
DONE. Owner clarified they had only seen a password prompt, not the shown key;
that attempt is not an exposure pass. The corrected key driver kept the screen
interactive only during preparation. Owner explicitly confirmed the full public
synthetic key was visible after Show. The controller then signaled allow-sleep,
and the runner confirmed SLEEP_ALLOWED (screen-on flag removed). With AOD still
enabled, owner lowered the wrist/awaited dimming and observed the Watch face with
no key or app data. The driver subsequently completed 1/1 with cleanup DONE.

Owner confirmed AOD restored to its original disabled state. Existing Samsung
keyboard and existing device credential were used; no PIN/value was captured.
A final actual-save/Lock observation and record-preservation verification is in
progress; its outcome is not inferred from earlier controlled-gate tests.

## Actual save followed by manual Lock

The save-lock fixture began at the prepared Setup key with normal session clocks
and a kept-on display. Owner submitted once, then pressed Lock Vault roughly five
seconds into the real cryptographic/storage operation. The key disappeared
immediately; after 45 seconds the app still showed no account list/codes and
reported: Account was saved. Unlock and review before adding it again.
No retry or password submission followed. After the finish signal the driver
reloaded only its public synthetic file: final record count 14, all original 13
records preserved in order, exactly one new record. It passed 1/1 with cleanup
DONE. This complements the controlled delayed-I/O tests with a real Watch write.

Manual host modes: accounts (natural sleep), wizard (keyboard preparation),
lock-check (active credential-lock preparation), key (kept on until the explicit
allow-sleep marker), and save-lock (real write plus record-preservation check).
Default observation duration is 180 seconds; explicit d2faManualSeconds accepts
60–600. Screen-on controls are debug/test-only; session clocks are never frozen.
Read-only synthetic startup inspection may be retried once through userAction
when launch/ambient invalidates it; no password cache, reset or format change.
Control markers reside only inside the reported validated synthetic directory.
A host pass is not a substitute for the owner's visual observation.

## Remaining limits

Physical evidence covers one SM-L705F/API 36 with the owner's settings and patch
level. API 30–32, other OEMs, casting/secure displays, separate IME capture,
accessibility-service usability, actual OS backup/transfer, hardware key invalidation,
full-volume exhaustion and actual OOM/low-memory-kill behavior were not verified.
No audit, cold-hardware benchmark, full-device resource stress or approved receiver
budget is claimed. Communication remains deferred to the owner's separate model.
#27/#30/#37/#13 are not closed by this partial evidence PR.
## Bounded capacity-fault model on Android

VaultCapacityFailureDeviceTest passed 1/1 on the physical Watch. It copied the
hashed public corpus, loaded its 13 records, prepared one synthetic addition,
and used the real Android temp-file writer with an injected capacity exception
after writing/flushing/syncing 64 bytes. Outcome NOT_COMMITTED; original encrypted
bytes unchanged; partial temp remained exactly 64 bytes; no backup was created;
all original records reloaded unchanged. Synchronous test cleanup removed its
owned files/directory. Failure diagnostics were then changed from list equality
to a boolean assertion to avoid printing record contents; success condition is
identical. This models a limited-write failure; it does not establish behavior
under a truly full volume, OOM, system-wide pressure or hardware power loss.

Core device regression subset now totals 16 passed, one emulator-only PIN skip.
Benchmarks and manual drivers are separate invocations and not added to that count.

Final restoration inspection: Samsung HoneyBoard remains selected; owner confirmed
AOD disabled again; no owned synthetic child processes; zero synthetic-capacity
directories. Seven earlier interrupted-run synthetic-restart directories remain
intentionally retained for diagnosis. No real vault or unrelated files were read
for these checks; no device credential was changed. Final source APK/test-APK
build passed after the diagnostic-only assertion change.
