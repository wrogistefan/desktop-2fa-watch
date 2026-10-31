# Security Model

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

Creation requires at least 8 characters and a heuristic score of 3 or more.
Mixed case, digits, symbols, and length of at least 12 add points. This is not
zxcvbn, an entropy estimate, or equivalent to the desktop password policy.
The UI unlocks an existing vault with its password without strength enforcement.

**Remember on watch** defaults to enabled. The app stores the actual vault
password in `EncryptedSharedPreferences`, with the preference master key
protected by Android Keystore. It does not replace the password-derived vault
key with a device-specific format. Cached-password access does not require a
per-use PIN/biometric prompt in this implementation.

Unavailable or corrupted preferences fall back to manual password unlock without
automatically deleting/recreating the cache or resetting the vault. Failed cache
writes and deletions are surfaced separately from the vault operation. Clearing
deletes all three known secure/legacy preference stores without decrypting them;
all deletion results must succeed. This also permits cleanup of an unreadable
cache. A failed clear warns that cached unlock may still be possible after restart.

## Sessions and locking

After unlock, the vault and secrets remain in process memory. Password and
form values can also remain in Compose state. **Lock Vault** immediately drops
the active vault reference, clears input state, changes to the unlock screen,
and schedules cache removal on an I/O coroutine with a non-cancellable clearing
section. Clearing also attempts to remove legacy plaintext fallback preferences.
Unlock submission waits for that cleanup attempt to finish, avoiding a concurrent
cache save being removed by the previous Lock action. Failure never keeps the
active vault open.

There is no inactivity/background lock or guaranteed memory zeroization.
Kotlin/JVM strings and garbage collection do not provide secure erasure.
Returning to the watch face should not be treated as an explicit vault lock.
The app currently does not block screenshots with `FLAG_SECURE`.

## File integrity and persistence

AES-GCM authenticates the encrypted payload. Wrong passwords and modified
ciphertext can surface as the same error. There is no rollback protection,
forward secrecy, or protection against deleting the vault. An older valid
copy can replace the current file without a freshness warning.

Existing-vault updates require atomic replacement after syncing the encrypted
temporary file and backup. There is no non-atomic fallback or automatic copy-back
on failure. Ambiguous artifacts are retained and cannot be overwritten by the
next save. Cleanup failures after a successful move do not roll back committed
data. The `.bak` is recovery evidence, not a permanent backup service. See
[Cryptography](crypto.md). Restart recovery and UI outcome reconciliation are
separate #21/#22 work; directory/power-loss durability is not established.
Startup distinguishes confirmed absence from inspection errors. Errors or a
leftover backup without a main file block setup. New-vault publication is
create-only, so a competing creator cannot be overwritten. Automatic backup
recovery and full power-loss durability are still separate work.
A failed initial write can leave a partial file that blocks setup. Creation is
not an atomic write of the entire vault and never automatically discards such a file.
Startup routing does not read cached credentials when absence or recovery is
reported. For a present file, cache/load failures select manual unlock, never
setup. Cancelled startup work does not publish a navigation result. This is not
the later session-generation/lifecycle protection tracked in #25/#26.

## Backup, transfer, and data loss

The configured Android cloud-backup and device-transfer rules exclude
`vault.d2fa`, `vault.d2fa.bak`, `vault.d2fa.tmp`, and the secure preference file; a legacy fallback
preference file is excluded too. The application manifest still allows backup
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
must be explicitly present as integer literal 1; missing or coerced values are
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
