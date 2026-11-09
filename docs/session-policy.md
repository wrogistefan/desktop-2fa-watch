# Session and remembered-password decision (#24 / C2)

Status: **accepted product policy, not implemented**. Approved by the repository
owner on 2026-11-09. This documentation-only decision follows roadmap #13.
Runtime baseline: `18b7804` (main before this decision); #23 is a separate draft
with cache-outcome tests, not evidence of the policy below being enforced.

## Decision and reason

Use the vault password to open every new session. Disable and remove the
Remember on watch option rather than leave a nonfunctional checkbox. Do not
store new passwords in preferences, read old cached passwords for unlock, or
automatically open a session on launch, restart or process recreation. The
default is the same for fresh installs and existing users; an old checkbox or
remembered password is not an opt-in to a future authentication mechanism.

This chooses a password-entry cost on the small screen in exchange for not
letting possession of the watch silently reopen a locked session. There is no
cache-based opt-in in this policy. A future convenience feature would need a
separate design/approval and appropriate device authentication; a device PIN
alone is not a substitute for the vault password in this implementation.

Explicit Lock, screen off, true departure to another app/watch face and 60
seconds of inactivity invalidate the session immediately. Returning, waking or
unlocking the device never opens the vault automatically. Fullscreen IME entry
and return are part of editing, not departure. Do not equate onPause, loss of
window focus or an IME overlay alone with true backgrounding.

## Transition and storage compatibility

This PR changes no runtime behavior, defaults or data. The implementation must:

1. Disable cached-password reads/writes for unlock on every startup path before
   attempting cleanup; do not rely on a once-only migration flag to prevent reuse.
2. Remove all three known secure/legacy preference stores without decrypting:
   `desktop_2fa_secure_prefs`, `desktop_2fa_fallback_prefs` and
   `desktop_2fa_secure_prefs_fallback`. Cleanup must be serialized with any
   remaining credential work and run off the UI thread.
3. If removal is false, throws or is unavailable, retain an actionable warning
   and retry at the next launch or an explicit cleanup action. A successful
   session still requires the supplied password; cleanup failure cannot enable
   cache unlock. Do not describe deletion as confirmed or silently dismiss its
   failure. Older builds may still use leftover cache after downgrade; that
   limitation must be explained when cleanup fails.
4. Never reset, rewrite or remove the vault in response to cache errors. Do not
   delete unrelated Keystore aliases. Keep every known credential store excluded
   from Android cloud backup and device transfer, including legacy names.

Existing users who relied on Remember must now supply their original vault
password. Warn about this change before distribution. There is no password-reset
or cached-password recovery promise if they have forgotten it; preserve their
encrypted file and direct them to independent service recovery methods. Do not
turn unreadable data into a fresh setup. Actual cryptographic secure erasure of
old disk blocks or JVM string copies is not promised.

The password-encrypted D2FA v1 file, Argon2id parameters, AES-GCM and supported
historical records remain unchanged. There is no vault migration, new key
wrapping scheme, receipt persistence, network permission or shared AGENTS
security-standard change. Backup exclusion does not provide user-facing backup
or restore a missing password/key on another watch.

## States and events

States: **closed** has no active decrypted vault; **opening** owns a temporary
password operation; **active** owns the vault/draft; **committing** owns a storage
attempt; **review** is the existing storage/recovery route. Authenticated
read-only recovery follows the same locking policy as active access.

Every asynchronous operation belongs to a session generation. Invalidating it
must prevent both success and error completions from restoring the old vault,
draft or navigation. Cancellation is supplementary: it cannot undo a completed
write. The next explicit unlock/review uses persisted state and B4 outcomes,
never blind automatic re-addition.

| Event / starting state | Required next state and effect | Password / cache rule |
| --- | --- | --- |
| Fresh install, confirmed absent file | Setup; no existing session | Require explicit new password; no Remember option |
| Existing supported file at launch, including after process death | Closed / manual unlock | Require vault password even if old preferences remain |
| Storage error or recovery artifacts | Review; preserve every candidate | No cached unlock; only authenticated read-only recovery where supported |
| Submit correct password, opening | Active only if operation still owns current generation | Keep password only as needed in process for the active vault/write; no persistence |
| Wrong password / rejected data | Closed or review according to existing storage outcome | Never setup/reset or substitute a cache value |
| Explicit Lock from active, committing or recovery | Invalidate generation; hide codes; detach vault; clear sensitive input/drafts immediately | Cache deletion result cannot postpone locking |
| Device screen off or OS device lock | Same invalidation, regardless of pending work | Wake/device unlock still requires manual vault unlock |
| True departure to watch face/other app | Same invalidation; stop code refresh | Return stays closed |
| 60 seconds without qualifying user activity | Same invalidation, including from wizard/recovery | No timer-driven or lifecycle-driven cache reopen |
| Fullscreen IME opens/closes, Next/Done or editor return | Remain in current editing session unless a real lock trigger occurred | Keep draft in memory; no saved state/Intent workaround |
| Activity recreation/process death | Discard session and unfinished input | New process requires manual unlock; preserve committed files |
| Late opening/add/recovery completion after invalidation | Stay closed/review, never publish old plaintext or show codes | Do not restore submitted password or draft |
| Write completes after Lock or cancellation | Keep UI closed; retain actual committed result or storage-review requirement | Explain possible saved change at next authenticated review; do not offer a blind retry |
| System wall clock changes | Session timeout uses monotonic time; refresh TOTP from current wall clock only while active | Resume cannot unlock or reset an expired session |
| Cleanup fails / Keystore unavailable | Manual unlock remains possible; keep cleanup warning | No cache read fallback or claim of successful deletion |

Qualifying activity means user editing, navigation, touch/rotary input or explicit
actions; code ticks, recomposition, storage completion and passive focus changes
do not extend the timeout. Measure the 60-second interval with monotonic time,
including its exact boundary. Screen off/true departure wins over any activity.
Opening a keyboard does not suspend the policy indefinitely. Fullscreen editor
typing must remain usable: verify native edits/Next/Done and their interaction
with inactivity on the actual IME. If the platform cannot reliably distinguish
IME editing from departure, record that as an implementation blocker instead of
shipping a heuristic that silently drops drafts or bypasses locking.

## Device, key and trust limits

A locked device does not establish that the current cache is authentication-bound:
the old master key has no per-use authentication requirement. The chosen policy
therefore does not use that key for opening sessions at all. Unavailable,
invalidated or missing keys affect only legacy cleanup/recovery limits, never
authorization to overwrite data. Hardware-backed keys are not assumed.

Do not put passwords, account keys, pending plaintext or active sessions into
saved instance state, navigation arguments, Intents, notifications or logs.
The app cannot protect against a compromised/rooted OS, malicious keyboard or
accessibility service, observation or all memory copies. Screenshot/recent-app/
ambient visual protections remain separately tracked in #27. Hiding references
and stopping refresh are not a secure-erasure claim or security audit.

## Implementation and verification gates

- #23 / C1: preserve immediate Lock, honest cache results and manual recovery;
  rerun the draft's instrumented tests successfully before merge.
- #25 / C3: session ownership, explicit Lock and late operations. Incorporate
  the approved disabling of cache unlock/write and legacy cleanup into a focused
  implementation before lifecycle locking is enabled. Update old warning wording:
  the new runtime ignores leftover cache, although older builds may reuse it.
- #26 / C4: enforce the approved 60-second/background/screen/device-lock events,
  stop inactive code refresh and preserve IME editing. Do not treat this decision
  as evidence that any trigger works already.
- #37 / C5: current emulator and physical-watch evidence, using synthetic data.

Review/test traces must cover fresh/existing users with Remember previously on
and off; readable, absent, corrupt and key-lost preferences; false/throwing
cleanup; cold/repeated launch; Lock during delayed startup/unlock/create/add and
before/after commit; exact 60-second boundary and user activity resets; screen
off, device lock and true departure; native fullscreen IME edits/Next/Done/Back;
restart/process death; wall-clock jumps; and read-only recovery. Verify hidden
codes stay hidden, drafts are cleared only on real session invalidation, existing
vault bytes survive cache errors, and committed additions survive restart.

Validation for this decision: source/state-table/documentation review and
`git diff --check` only. No new Gradle, emulator or physical-watch tests are
claimed for this documentation-only PR. The acceptance is a product decision,
not proof of security or readiness to receive secrets.
