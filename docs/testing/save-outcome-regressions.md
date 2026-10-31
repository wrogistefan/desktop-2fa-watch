# Save Outcomes and Post-commit Verification (#22)

Scope: explicit existing-vault update outcomes and truthful manual-add feedback.
All fixtures, passwords, account names, fault diagnostics and files are synthetic.
This builds on #20 publication and #21 read-only review; it does not change v1,
KDF, account identity/collision policy, networking or release configuration.

## Contract and threat analysis

`Vault.save(file, password)` now returns `VaultSaveOutcome`. All production update
calls go through `persistAccountCandidate`; callers must inspect the outcome.
`saveNew` retains its separate create-only exception contract. Validation and
encryption preparation can still throw before publication. This is a Kotlin source
API change inside the app, with no published SDK or stored-data migration.

| Situation | Outcome / feedback |
| --- | --- |
| Known pre-publication failure, including existing recovery artifacts | NOT_COMMITTED |
| Atomic move explicitly unsupported | NOT_COMMITTED; no fallback |
| Other exception while attempting publication, before or after the actual move | UNCERTAIN; preserve evidence |
| Move returns successfully and backup cleanup succeeds | COMMITTED |
| Move returns successfully, backup cleanup fails | COMMITTED_WITH_RECOVERY_ARTIFACTS |
| Verification read fails or returns a different complete snapshot | Keep known commit outcome, mark verification failed |
| Unexpected exception from the writer | Conservatively UNCERTAIN, never assume no write |

The move-return commit point does not promise parent-directory or power-loss
durability. An exception near publication is not proof of rollback. File handling
still never copies an older backup over an uncertain result or deletes evidence
on failure. Cleanup errors cannot turn a known commit into a failed write.

Only a clean COMMITTED result with matching post-save verification publishes a
new writable UI state. Every other result clears the submitted draft, detaches
the active vault and enters storage review. Messages distinguish not-saved,
saved-but-unverified, saved-with-artifacts and uncertain. No raw exception strings
are shown. Retry re-inspects storage; it never replays the submitted addition.
Input-validation feedback before the storage attempt still preserves the form.

Cancellation is propagated; it is not evidence that a synchronous filesystem write
was interrupted or rolled back. Session generation, late completion and lifecycle
handling remain #25/#26, and multi-operation serialization remains #36. This does
not establish transfer retry identity/idempotency (#32); the manual name-collision
check is not a persistent transfer identifier.

## Regression tests

Eight `AccountSaveResultTest` tests use actual encrypted old/candidate vaults with
injected filesystem/verification boundaries. They cover precommit failure,
unsupported atomic move, exceptions before/after real replacement, failed reload,
post-commit cleanup failure, matching/mismatched verification, an unexpected writer
exception after a real save, and cancellation before/after commit. Assertions check
exact preserved bytes, actual loaded fields, one write/no automatic retry, no
verification before commit, safe feedback and retained backups. After a committed
write with failed reload, explicit reconciliation sees the new account and the
ordinary manual-name check refuses resubmission; no global identity claim is made.

Existing replacement fault tests now also assert commit uncertainty by phase.
Production update round-trip tests check the returned COMMITTED result on JVM and
Android. Existing wrong-password/tamper/truncation, version, bounded-input, RFC,
cache and restart suites remain active.

`AccountSaveOutcomeDeviceTest` creates a disposable encrypted Android vault,
commits an account, injects a reload failure, authenticates the saved file, and
renders the production storage-review component in an isolated Compose host.
It verifies the committed message/review action with no Add Account or blind
Try again action, and unchanged bytes after rendering. It does not launch
MainActivity or inspect the real vault/cache. MainActivity's result routing is
reviewed in source; no new full-app IME/lifecycle/UI pass is claimed.

## Verification and limits

Local run: 2026-10-31, read-only `Wear_OS_Large_Round` AVD, Android 17 / API 37,
explicitly selected as `emulator-5580`, with
`-read-only -no-snapshot -no-window -no-audio` (no saved AVD changes).

| Command | Result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest --stacktrace` | Passed: 303 tests, zero failures/errors |
| `.\gradlew.bat :app:assembleDebug --stacktrace` | Passed |
| `.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | Passed: 11 tests, zero failures/errors; test APK rebuilt |
| `git diff --check` | Passed |

JVM/emulator results do not
establish physical power-loss durability, hardware Keystore, full lifecycle/IME
behavior or secure memory erasure. Read-only recovery still does not repair or
promote files; non-clean outcomes may require access through that restricted view.
