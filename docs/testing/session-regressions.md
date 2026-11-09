# Session ownership and password-only sessions (#25)

Date: 2026-11-09. Baseline: `86ad5b6` (merged #49), branch
`fix/25-session-ownership`, development app 0.2.0. No release or format bump.

## Scope and threat review

The accepted #24 password-only transition is now implemented: startup never
reads/decrypts remembered credentials, setup/unlock never save them, and there
is no Remember option. Startup and Lock delete all three known preference stores
without deleting unrelated Keystore keys. False/exception outcomes remain
warnings through manual unlock. Current code ignores residual cache, while old
builds may reuse it after downgrade. Existing users need their original vault
password; no cached-password recovery, password reset or data erasure guarantee
is provided. No vault rewrite or migration follows a cache/key failure.

`VaultSession` owns transient state and operations in the composition's coroutine
scope. Its generation changes before Lock/disposal clears passwords, vault,
wizard, recovery and success references. UI values/snapshots are captured before
I/O; both late success and error publication check ownership. Work queued after
invalidation/cancellation is skipped. Already-started synchronous writes finish
their existing protocol in an I/O non-cancellable section. Cancellation/Lock is
not a rollback and can happen after a successful commit.

Lock remains immediate and is available in the loading screen and wizard,
including during saving. New actions wait for pending work and requested legacy
cleanup. A late commit reports its known result, while uncertain/failing writes
require conservative storage review; neither path reopens Main or restores the
submitted draft. Users explicitly unlock/review before another addition.

A process-local coroutine mutex serializes session I/O across recreated Activity
owners, including inspection before a previous owner's write finishes. This is
not a multi-process lock or complete batch/stale-snapshot mutation API (#36).
Vault v1, Argon2id, AES-GCM, backup exclusions and offline operation are unchanged.
The shared AGENTS security standard is unchanged. Captured strings can survive
until blocking work ends; these checks do not prove secure memory erasure.

## Tests

- JVM session tests use separate UI/I/O executors and controlled gates, not
  sleeps for ordering. They exercise startup, setup, unlock, inventory, recovery
  and add completion/error after Lock; pre-dispatch invalidation; wrong password;
  cancellation before work/after commit; disposal/new ownership; and a recreated
  owner waiting for an old write. Successful additions preserve the old snapshot
  and persist once. These use a fake storage boundary, not device durability.
- Startup tests now assert that present files always require manual unlock.
  The old helper's cache/load callbacks and auto-Main path are removed rather
  than retained as a second startup route.
- Updated immediate-Lock tests use production password actions after proving
  legacy cache cannot auto-open the vault. Delayed failed deletion still hides
  codes and keeps unlock disabled; the warning now explains older-version reuse.
- New production-screen tests block real unlock/add completion, press Lock,
  recreate the Activity, verify no plaintext/draft restoration, and explicitly
  reload a single committed addition without losing the original entry.
- A new non-exported debug host in dedicated `:synthetic_session` opens a
  synthetic vault/draft. Its controller verifies process name/UID/PID ownership
  before killing only that child without finish/onDestroy. A new process must
  report a closed, clean session and unchanged committed bytes. Intents/markers
  contain only a validated synthetic directory, stage and constant PID/status;
  the compiled fixture password/key are public synthetic data, never user data.

All Android fixtures use disposable validated app-cache directories and prefixed
preferences. Finally blocks clean fixture files/preferences and owned children.
The normal vault and real credential stores are never opened by these tests.
Direct password/wizard actions in the controller tests are intentional: they
verify production callbacks/state/Compose rendering, not native keyboard entry.

## Verification

Use the repository Wrapper, SDK 37 and configured Gradle 9.6.0 / Java 25 daemon.
Local environment sets `GRADLE_USER_HOME=C:/Users/lukas/.gradle`. Android uses
`ANDROID_SERIAL=emulator-5582`, `Wear_OS_Large_Round`, Android 17 / API 37, started
with `-read-only -no-snapshot -no-window -no-audio -gpu swiftshader`.

| Command / check | Result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest --stacktrace` | Passed: 315 JVM tests, zero failures/errors; app/test APK builds and release manifest processing passed |
| Merged release manifest | No synthetic component or INTERNET permission |
| `git diff --check` and relative Markdown links | Passed |
| `.\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache --stacktrace` | In progress; final device results are recorded after completion |

During development an initial compile caught the not-yet-migrated startup
signature, and a controlled test caught that disposal cleared secrets but left
the old screen enum. Both were corrected; a later targeted session/startup JVM
run passed before the full suite was launched. They are not final-pass evidence.

No automatic background/screen-off/device-lock/inactivity trigger is implemented
here (#26). No new physical-watch/fullscreen IME/ambient pass, hardware-backed
Keystore, full power-loss durability, broad cross-process writer coordination or
security audit is claimed (#37/#36). Earlier reports remain historical evidence.
