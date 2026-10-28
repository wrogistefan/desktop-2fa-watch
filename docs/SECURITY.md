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

Preference initialization attempts recreation if protected storage is
unavailable or corrupted. This can discard the cache; the encrypted vault is
not intentionally reset by that path. Cache read/write/clear operations can
fail, and their Boolean results are not surfaced in the UI. A remember or lock
action is therefore not a verified guarantee of successful disk-cache mutation.

## Sessions and locking

After unlock, the vault and secrets remain in process memory. Password and
form values can also remain in Compose state. **Lock Vault** immediately drops
the active vault reference, clears input state, changes to the unlock screen,
and schedules cache removal on an I/O coroutine with a non-cancellable clearing
section. Clearing also attempts to remove legacy plaintext fallback preferences.

There is no inactivity/background lock or guaranteed memory zeroization.
Kotlin/JVM strings and garbage collection do not provide secure erasure.
Returning to the watch face should not be treated as an explicit vault lock.
The app currently does not block screenshots with `FLAG_SECURE`.

## File integrity and persistence

AES-GCM authenticates the encrypted payload. Wrong passwords and modified
ciphertext can surface as the same error. There is no rollback protection,
forward secrecy, or protection against deleting the vault. An older valid
copy can replace the current file without a freshness warning.

Saves use a synced temporary file, request atomic replacement, and have a
non-atomic fallback. The `.bak` is temporary recovery support during that save,
not a permanent backup or crash-recovery service. See [Cryptography](crypto.md).

## Backup, transfer, and data loss

The configured Android cloud-backup and device-transfer rules exclude
`vault.d2fa`, `vault.d2fa.bak`, and the secure preference file; a legacy fallback
preference file is excluded too. The application manifest still allows backup
for other eligible app data. These rules are not an implemented export feature.

Uninstalling, clearing app storage, watch resets, or loss of the password can
make accounts inaccessible. There is no user-facing export, recovery service,
or password-reset mechanism. Keep independent service recovery methods.

## Known validation boundaries

Loaded Kotlin models do not fully validate Base32, digit counts, periods, or
algorithm names. Generation has fallbacks that may hide invalid parameters.
Unknown JSON fields are discarded on save. Source-level format parity does
not establish complete desktop interoperability; follow
[issue #10](https://github.com/wrogistefan/desktop-2fa-watch/issues/10).

## Network behavior

The current app has no `INTERNET` permission and no implemented account sync,
analytics, or cloud workflow. The Wearable dependency alone does not constitute
a transfer feature. ADB over Wi-Fi is a development connection outside the
normal application workflow; turn debugging off when finished testing.
