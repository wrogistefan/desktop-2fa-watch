# Desktop-2FA for Wear OS User Manual

This manual covers the development version after the 0.2.0 MVP. The app runs independently on a Wear OS watch
and generates TOTP codes locally. A phone companion and cloud account are not required.

## Installation

Build and run the app from Android Studio, or install the debug APK using ADB.
See [Development](development.md) for build requirements and Wi-Fi pairing.
This documentation does not assume a Play Store release is available.

## Create a vault

On first launch, **Create Vault** asks for a master password. The current UI
requires at least 8 characters and a heuristic score of at least 3. Mixed case,
digits, symbols, and length contribute to the score. Use a long, unique password;
the score is not a measured estimate of resistance to guessing.

**Remember on watch** is checked by default. If enabled, the app attempts to
store the password in encrypted preferences protected by Android Keystore and
can unlock automatically on later launches. If disabled, enter the password
again after a fresh launch. Keep your own record of the password either way.

Tap **Initialize Vault** and wait for key derivation and storage to finish.
The app currently asks for the password once, without a confirmation field.
Setup refuses to replace an existing vault, including a file created after the
startup check. If storage cannot be inspected or a backup needs recovery, the
app shows a storage error and retry action instead of opening setup.
Use **Retry storage check** to inspect again; repeated inspection errors do not
enable creation. If cached unlock fails for an existing file, the app offers
manual unlock instead. No error automatically replaces the vault or its backup.
If the very first write is interrupted, an incomplete file may remain. The app
will refuse to overwrite it; this version has no automatic reset/recovery flow.

## Unlock an existing vault

If no usable cached password is available, **Unlock Vault** prompts for the
master password. The same remember option controls whether it is cached.
A failed cache lookup does not replace or reset the vault.
If remembering or removing the password fails, the app displays a separate
warning. The vault can still be unlocked with its password. A removal warning
means automatic unlock after restart may still be possible.
To request cache cleanup again, unlock with **Remember on watch** unchecked.
An unavailable Keystore key may require continuing with manual password unlock;
removing the cache does not repair or reset the Keystore key.

Unlocking a vault does not reapply the creation strength policy. The password
must match the one used to encrypt it. The UI rejects blank password input.

## Add an account

Scroll to **Add Account**. The wizard shows one field per screen, with its name
above it and a step indicator:

| Step | Field | Example |
|------|-------|---------|
| 1 of 3 | Service name | `GitHub` |
| 2 of 3 | Account name | `demo@example.com` (required and unique) |
| 3 of 3 | Setup key | The service's Base32 manual setup key |

Use **Next**, or the keyboard's Next action, to advance. **Back** preserves the
draft; **Cancel** discards it without saving. Hardware back moves to the previous
step, or cancels from the first step. With the keyboard open, the system may
dismiss it before navigating. During saving, navigation and repeated
submissions are disabled.

Autofill is disabled in the app; the keyboard is asked to disable autocorrection
and suggestions. Exact keyboard behavior depends on the watch IME. The layout
accounts for IME insets and scrolls when space is limited. Submission dismisses
the keyboard so validation feedback can be read on the current step.

Setup keys use ASCII letters A–Z/a–z and digits 2–7; ASCII spaces, tabs and
line-formatting whitespace are removed. Other Unicode letters/whitespace are
rejected rather than folded into a different key. Both correctly
padded and unpadded Base32 are accepted, fully decoded, and checked for canonical
trailing bits. Padding is added before storage when needed. `0` and `1` are not
Base32 characters. **Show / Hide** lets you inspect the input. Invalid
characters and incomplete keys are explained on submission, without showing an
error on every keystroke. `JBSWY3DPEHPK3PXP` is synthetic test data, not a key
for a real account.

Tap **Save Account** to finish. Failed saves preserve the draft for retry; a
successful save returns to the new card with **Account added** confirmation.
New accounts use SHA1, 6 digits, and a 30-second period; there is no parameter
editor in this version.

QR scanning, `otpauth://` entry, renaming, deleting, password changes, and
import/export are not available in the current UI.

## Read codes

Each card shows the issuer, account name, code, and seconds remaining.
The list refreshes its time value approximately once per second while visible.
Codes depend on the watch clock; check date/time synchronization if a service
rejects them. `Code unavailable` means generation failed; that card shows no code
or countdown. Check the account parameters and watch time, then retry/reopen the
app. Other valid cards continue working. The app does not substitute settings.

Duplicate account names and unnamed entries already present in a loaded vault
can be displayed. Adding a new entry with an existing account name is rejected.
Loaded accounts must have a valid non-empty Base32 key, SHA1/SHA256/SHA512,
6/7/8 digits and a positive period. Invalid historical accounts cause the vault
to be rejected intact; they are never silently removed or rewritten. Use a
compatible reader and keep service recovery methods when repairing original data.

## Lock the vault

Scroll to **Lock Vault**. The app immediately leaves the account view, clears
its active vault reference and form inputs, and requests removal of the cached
password in the background. This does not delete the encrypted vault.
The unlock action waits for that cleanup attempt. If removal fails, codes stay
hidden and a warning explains that the remembered password may remain on disk.

There is no automatic inactivity lock. Returning to the watch face is not an
explicit lock action. Clearing references does not guarantee memory erasure;
see the [security model](SECURITY.md).

## Storage, updates, and recovery

The vault is stored as `vault.d2fa` in private app storage. A normal update with
the same app ID and signing key preserves it. Uninstalling, clearing app data,
or resetting the watch can remove it. Configured Android backup/transfer rules
exclude the vault and credential preferences. The temporary `.bak` used during
saving is not a user backup or a supported restore mechanism. Updates now require
atomic replacement; unsupported storage or leftover `.tmp`/`.bak` candidates can
block saving. Do not delete app data or retry by recreating the vault. Failed
updates retain available recovery evidence; automatic promotion/repair is not implemented.
Vault files are limited to 10 MiB. Oversized/failed reads are rejected without
replacing the file, including when its size changes during reading.
If a backup or pending file remains (including beside the main file), creation
and writable unlock are blocked. Choose **Review recovery files**, enter the
password for the selected file, then select **Current vault**, **Previous backup**
or a pending file. Successfully authenticated supported files open a clearly
marked **read-only recovery view**. Codes are available, but adding accounts is
disabled, the supplied password is not remembered, and no files are changed.
**Lock Vault** closes the view and returns to storage/recovery selection.

This is access to recoverable codes, not a repair or permanent restore. The app
does not choose the newest file, merge accounts, promote a backup or discard any
candidate. Multiple files may hold different accounts. Keep independent service
recovery methods; do not clear app storage or delete recovery files to dismiss
the error. Files with unknown JSON fields are rejected intact. Open such files only
with a version that understands those fields; no metadata is silently stripped.
The encrypted header and JSON payload must both declare supported v1. Missing
or mistyped payload versions are rejected rather than guessed. Regular generated
v1 vaults need no migration. Keep unsupported originals; use a compatible reader
to inspect and explicitly save a separate supported copy, never clear app data
or remove unknown fields from the original just to bypass an error.

The MVP has no user-facing backup/export or forgotten-password recovery.
Keep the service's recovery codes or another authenticator before relying on
this app. Desktop transfer is planned in
[issue #10](https://github.com/wrogistefan/desktop-2fa-watch/issues/10), not yet
available as an app workflow.

## Troubleshooting

| Symptom | What to check |
|---------|---------------|
| Loading takes time | Argon2id uses 128 MiB per derivation; performance varies by device |
| Invalid password | Check the entered password; a modified encrypted payload can produce the same failure |
| Unsupported vault format / invalid data | Only the `D2FA` v1 header and explicit integer payload version 1 are accepted; keep the original when unsupported fields or metadata are reported |
| Invalid Base32 | Enter the manual setup key, not a QR image or `otpauth://` URL; check padding and length |
| Duplicate name | Choose a different account name for the new entry |
| `Code unavailable` or rejected code | Check account parameters and the watch clock; no substitute code is generated. If it persists, report the app/device version using synthetic examples, never real keys or codes |
| Remember setting does not persist | Protected preference creation or saving may have failed; unlock manually |

Report reproducible problems with synthetic data through
[GitHub Issues](https://github.com/wrogistefan/desktop-2fa-watch/issues).
