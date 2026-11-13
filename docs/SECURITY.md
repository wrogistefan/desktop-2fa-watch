# Security Model

The [accepted Watch v2 design](transfer/vault-v2-proposal.md) defines the
transfer-receipt format, header AAD, explicit opt-in migration, retained encrypted
v1 safety copy and read-only recovery. The v2 codec/receipt validation is now
implemented alongside the unchanged v1 format. An explicit migration backend is implemented but has no app entry point.
Read-only archive recovery and internal migration/transfer session ownership are implemented; the end-user consent UI and network receiver remain pending. Design acceptance is not test evidence or release approval;
Desktop wire compatibility does not imply direct v2 file compatibility.

This describes the 0.2.0 MVP and its current boundaries. The app has not had an
independent security audit. Report vulnerabilities through [Security Policy](../SECURITY.md).

## Assets and trust boundary

The assets are TOTP secrets, the vault password, derived keys, and generated
codes. The encrypted file lives in Android's private app storage. The model
assumes a trustworthy watch OS, app build, cryptographic providers, and device
access controls. It does not protect against a compromised/rooted OS, debugger
access to the app, malicious accessibility tooling, or observation of the screen.

An attacker with only a copied vault must guess its password to decrypt the
payload. Argon2id increases the cost of each guess; there is no online lockout
for an offline attack. Encryption cannot make a weak password strong.

## Password policy and caching

The [accepted session policy](session-policy.md) is implemented by #25/#26:
password-only sessions with screen-off/device-lock, Activity-stop and 60-second
inactivity locking.

Creation requires at least 8 characters and a heuristic score of 3 or more.
Mixed case, digits, symbols, and length of at least 12 add points. This is not
zxcvbn, an entropy estimate, or equivalent to the desktop password policy.
The UI unlocks an existing vault with its password without strength enforcement.

Remember and automatic cached unlock are removed for new and existing users.
Every new session requires the original vault password. Password input is
captured on the UI thread and cleared on submission; the password needed to
save an active vault remains only in its in-memory session owner.

Startup and Lock attempt to delete all three secure/legacy preference stores
without reading/decrypting them or deleting Keystore aliases. Cleanup failure is
reported honestly, remains visible through manual unlock, and is retried at the
next startup or Lock. This version ignores leftover cache; older builds may
reuse it after downgrade. No cleanup failure resets or rewrites the vault.
Users who relied on Remember must know their original password before updating;
there is no new password-reset or cached-password recovery mechanism.

## Sessions and locking

`VaultSession` belongs to the composition and serializes its actions on the UI
scope. Lock replaces its generation token before hiding codes, detaching the
vault and clearing password/draft/recovery references. The same cleanup happens
on composition disposal. Startup, setup, unlock, recovery, save success and error
callbacks check ownership before publishing. An old completion cannot open Main,
restore plaintext or repopulate an invalidated draft.

A single process-local coroutine mutex prevents a new Activity owner from reading
while the old owner's I/O is still finishing. This is process-local coordination,
not a multi-process lock. Work queued after invalidation or
cancellation is skipped. A synchronous write already started runs through the
existing atomic-save protocol in an I/O non-cancellable section; cancelling its
caller cannot undo a known commit. Lock does not wait for it to hide codes.
Further actions wait for the pending operation/credential cleanup. A late save
requires storage review with its actual known/uncertain result and manual unlock,
never a blind automatic retry. A non-secret, file-scoped in-memory completion
notice hands a disposed owner's outcome to a new Activity and survives further
Activity recreation until explicit storage Retry acknowledges it. Notice identity
prevents an old callback from clearing a newer outcome. This completion notice
is memory-only; whole-process death loses it. It is separate from v2 encrypted
transfer receipts. There is still no receiver producing those receipts, so
inspect committed accounts after a restart before manually retrying. Recreated processes never recover an active
session from saved state or cached credentials.

Manual and internal batch additions use one privately copied, validated candidate
and reload under the session I/O mutex. A snapshot that differs from committed
data is rejected before saving, preserving another live owner's changes. Each
batch is one file update and one B4 outcome, never a series of partial additions.
The extra authenticated read keeps v1 KDF costs and needs physical-watch
measurement in #30. After 30 seconds of pending I/O the UI offers a recovery
notice. It never releases the writer mutex on timeout: if I/O cannot finish,
force stop/reopen the app or restart the watch without clearing app data, then
inspect storage. A completed write cannot be assumed rolled back.

Screen off/device lock, Activity stop and 60 seconds without qualifying input
invalidate the session, including opening/recovery/wizard operations. The timeout
uses elapsed realtime, checked before input and I/O publication: a late edit
cannot extend an already expired session. Touch, rotary/key input and UI/IME
callbacks renew it; ticks, focus changes and I/O do not. Pause or focus loss alone
hides codes and cancels refresh without discarding an unexpired draft. Resume
reads current wall time before displaying codes. No wake lock or background
refresh is used. See [lifecycle checks](testing/lifecycle-regressions.md).

Input/deadline and I/O-publication checks also reconcile actual window focus;
the focus callback remains authoritative during its transition. This fixes a
stale hidden-code state after Activity recreation without restoring its old
session. See [integrated checks and physical limits](testing/wear-integration-2026-11-09.md).

There is no guaranteed memory zeroization. Strings captured by running I/O can
remain until that operation finishes; JVM garbage collection cannot prove
erasure. MainActivity requests secure-window capture protection on all screens
and disables task screenshots on API 33+. This does not protect native IME
windows, cameras, compromised OS/accessibility providers or establish ambient
redaction. See the [screen-exposure assessment](testing/screen-exposure-2026-11-09.md)
for tested behavior and missing physical/API checks (#27).
Physical-watch/OEM fullscreen IME integration still needs #37 verification.

## File integrity and persistence

Name-based library lookup/removal rejects ambiguous historical matches rather
than choosing by order. Exact account names precede issuer fallback; failure
does not remove a record. This is not an account identity scheme: future transfer
must review all collision candidates explicitly. See [lookup checks](testing/lookup-regressions.md).

AES-GCM authenticates the encrypted payload. Wrong passwords and modified
ciphertext can surface as the same error. There is no rollback protection,
forward secrecy, or protection against deleting the vault. An older valid
copy can replace the current file without a freshness warning.

Existing-vault updates require atomic replacement after syncing the encrypted
temporary file and backup. There is no non-atomic fallback or automatic copy-back
on failure. Ambiguous artifacts are retained and cannot be overwritten by the
next save. Cleanup failures after a successful move do not roll back committed
data. The `.bak` is recovery evidence, not a permanent backup service. See
[Cryptography](crypto.md). The save result distinguishes precommit failure,
commit, retained cleanup artifacts and uncertain publication. A failed verification
read cannot turn a known commit into a retryable addition. Non-clean outcomes
detach the UI and clear its draft before storage review; no raw exception text is
shown. Directory/power-loss durability is not established.
Startup distinguishes confirmed absence from inspection errors. Errors or a
leftover backup/pending data, with or without a main file, block setup and writable
unlock. Authenticated recovery views expose codes read-only without promoting or
deleting any candidate. Their passwords are not cached and adding accounts is
disabled. No writable repair or cleanup is promised; multiple valid files can
contain different accounts. Startup/Lock clean up legacy remembered credentials;
recovery never uses them. New-vault publication is
create-only, so a competing creator cannot be overwritten. Automatic backup
promotion and full power-loss durability are not implemented.
A failed initial write can leave a partial file that blocks setup. Creation is
not an atomic write of the entire vault and never automatically discards such a file.
Startup never reads cached credentials or authenticates a file. A present file
requires manual unlock; a rejected load never permits setup. Cancelled or
invalidated startup work cannot publish a navigation result. Lifecycle locking
uses the same session invalidation path.

Vault reads refuse a final-component symlink at open time. Recovery also checks
the listed file's temporary metadata fingerprint before and after loading; a
detected replacement/change requires refreshed selection. File keys are provider
dependent and optional; these checks do not establish immutable file identity
against a malicious same-UID/root actor or override Android filesystem permissions.
The app-private parent directory/trustworthy OS boundary remains unchanged.

## Backup, transfer, and data loss

The configured Android cloud-backup and device-transfer rules exclude
`vault.d2fa`, `vault.d2fa.bak`, `vault.d2fa.tmp`, and the secure preference file; a legacy fallback
preference files are excluded too, including `desktop_2fa_secure_prefs_fallback.xml`.
All three known credential stores are excluded from cloud backup and device
transfer. The application manifest still allows backup
for other eligible app data. These rules are not an implemented export feature.

Uninstalling, clearing app storage, watch resets, or loss of the password can
make accounts inaccessible. There is no user-facing export, recovery service,
or password-reset mechanism. Keep independent service recovery methods.

## Known validation boundaries

Loaded entries, additions and saved snapshots now share strict Base32 and TOTP
parameter validation before mutation/persistence. Invalid snapshots are rejected
before writes/KDF; invalid loaded accounts never expose a writable vault.
This reduces malformed-input and silent-rewrite risks without changing encryption.
Models remain plain serializable data; callers must use the shared validation at
their boundary. Generation validates independently and reports a controlled
failure instead of silently changing parameters or returning a code-shaped
placeholder. UI errors contain no raw provider diagnostics. The JVM suite now
checks the full RFC 6238 table and independent parameter/boundary vectors. These
tests do not constitute a security audit or establish device lifecycle behavior.
Unknown JSON fields at either the root or entry level are now rejected on load,
before a writable vault is exposed. Original bytes are left untouched; no
automatic stripping or migration is performed. Source-level format parity does
not establish complete desktop interoperability; follow
[issue #10](https://github.com/wrogistefan/desktop-2fa-watch/issues/10).
The binary header and payload versions are independent checks. Payload version
must be explicitly present as an integer literal matching the supported header (1 or 2); missing or coerced values are
not accepted as evidence of v1. The writer refuses unsupported model versions
before mutation. This prevents ambiguous/lossy rewrites without changing KDF or
encryption; it does not add rollback protection or recovery for unsupported data.
Reads consume at most the 10 MiB vault limit plus one overflow sentinel, with
streams closed on every outcome. An early file-size hint is not trusted as the
read bound. Overflow and I/O failures never expose partial data as a vault or
modify the source file; this bounds input consumption without lowering KDF costs.

## Network behavior

The current app has no `INTERNET` permission and no implemented account sync,
analytics, or cloud workflow. The Wearable dependency alone does not constitute
a transfer feature. ADB over Wi-Fi is a development connection outside the
normal application workflow; turn debugging off when finished testing.

V2 payload failures retain fixed diagnostic stages (UTF8, JSON_SYNTAX,
JSON_TYPES, MODEL_SCHEMA, MODEL_VALIDATION), not original parser exception
causes that could contain decrypted account/key text. This supports storage
failure attribution without exposing input through an exception chain.

The [migration backend](testing/vault-migration-backend-2026-11-12.md) preserves
an exclusive encrypted v1 safety copy and never restores it automatically.
It is not callable from the UI yet. Its future caller must use the session writer
mutex and ownership/locking rules, inventory the copy during startup/recovery,
and require local consent. The retained copy is app-private data, not an external
backup; uninstall/reset/clearing app storage can remove it along with the vault.
It does not include later additions and cannot recover a lost password.

### Migration recovery inventory

The Watch storage boundary also inspects the fixed no-backup safety-copy path.
Only positive absence permits setup; residual migration directories block empty
initialization when main is missing. A retained archive does not block a healthy
main. Unknown metadata or symlinks/nonregular paths require review. The inventory
is metadata, not authentication: explicit selection plus bounded password-authenticated
v1 loading and before/after fingerprint checks are required for read-only access.
The existing session forbids edits/saves in recovery mode. No automatic restore,
rollback, cleanup, downgrade or merging is provided. The copy may be stale and
does not recover a lost password. App-private metadata checks assume a trusted
OS; they are not a defense against an OS/root attacker replacing files. Actual
Android backup/device-transfer, lifecycle and physical durability evidence is
still required. No transfer receiver is enabled by this inventory change.
