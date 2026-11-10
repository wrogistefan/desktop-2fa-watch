# Desktop-2FA for Wear OS User Manual

This manual covers the development version after the 0.2.0 MVP. The app runs independently on a Wear OS watch
and generates TOTP codes locally. A phone companion and cloud account are not required.

## Installation

Build and run the app from Android Studio, or install the debug APK using ADB.
See [Development](development.md) for build requirements and Wi-Fi pairing.
This documentation does not assume a Play Store release is available.

## Create a vault

**Changed for existing users:** Remember and automatic cached unlock are removed.
Keep your original vault password and independent service recovery methods before
updating. Every new launch/session now requires that password. Legacy remembered
credentials are scheduled for removal without resetting the vault. If cleanup
fails, deletion is unconfirmed: data may remain and older app versions could use
it. This version ignores it for unlocking. There is no password-reset or
cached-password recovery feature. See the [session decision](session-policy.md).
Screen off/device lock, leaving the app (Activity stop) or 60 seconds without
input locks the vault and clears unfinished input. Returning or waking the
watch requires the vault password. Opening the keyboard alone preserves an
unexpired draft; edits renew the deadline, but merely leaving the keyboard open
does not. Codes pause while the app is paused or lacks window focus.

On first launch, **Create Vault** asks for a master password. The current UI
requires at least 8 characters and a heuristic score of at least 3. Mixed case,
digits, symbols, and length contribute to the score. Use a long, unique password;
the score is not a measured estimate of resistance to guessing.

There is no Remember checkbox. Keep your own record of the password.

Tap **Initialize Vault** and wait for key derivation and storage to finish.
The app currently asks for the password once, without a confirmation field.
Setup refuses to replace an existing vault, including a file created after the
startup check. If storage cannot be inspected or a backup needs recovery, the
app shows a storage error and retry action instead of opening setup.
Use **Retry storage check** to inspect again; repeated inspection errors do not
enable creation. An existing file always requires manual password unlock. No error automatically replaces the vault or its backup.
If the very first write is interrupted, an incomplete file may remain. The app
will refuse to overwrite it; this version has no automatic reset/recovery flow.

## Unlock an existing vault

**Unlock Vault** always prompts for the original master password. No cached
password is read and no newly entered password is saved in preferences. A failed
unlock or legacy-credential cleanup never replaces or resets the vault. A cleanup
warning means deletion was not confirmed; this version still requires a password.
Startup and **Lock Vault** retry legacy cleanup without needing a readable
Keystore key. Do not clear app data to repair cache/key errors.

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

Tap **Save Account** to finish. A verified successful save returns to the new card
with **Account added** confirmation. If storage fails or the result needs review,
the app clears the submitted draft and opens storage checking instead of offering
to add it again. Read the message: it distinguishes **not saved**, **saved but not
verified**, **saved with recovery files**, and **uncertain**. Do not re-add an account
until you have checked existing data. **Retry storage check** re-inspects the vault;
it does not repeat the addition. Keep the source/recovery method for a new account.
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

Choose **Lock Vault** on the account list, account wizard or loading screen.
Codes disappear immediately and password, draft and active-vault references are
cleared. This does not delete the encrypted vault. A previously started storage
operation may still finish; controls remain disabled until it and legacy-cache
cleanup finish. A late save reports whether data was saved or needs review,
without reopening the session. Use storage review/manual unlock to inspect
existing entries before adding the same account again. Locking is not an undo.
Closing/recreating the activity or process also discards unsaved drafts and
requires a new password session; committed entries remain in the encrypted file.
If a save finishes after the activity closes, a new activity shows storage review
before another unlock. This notice survives further activity recreation until
**Retry storage check** acknowledges it. It is not a permanent history: after
the whole process restarts, inspect existing accounts before repeating an add.
After 30 seconds of pending I/O a notice explains that storage is still busy.
Lock still hides codes immediately. If it never finishes, force stop and reopen
the app or restart the watch; do not clear app data. The write may have completed.

Screen off/device lock, Activity stop and 60 seconds without input lock the
session. Manual Lock remains available at any time. Clearing references does
not guarantee memory erasure; see the [security model](SECURITY.md).

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
password you intend to use, then tap **Current vault**, **Previous backup**
or a pending file. Successfully authenticated supported files open a clearly
marked **read-only recovery view**. Codes are available, but adding accounts is
disabled, the supplied password is not remembered, and no files are changed.
**Lock Vault** closes the view and returns to storage/recovery selection.
If a selected file changes, return to storage checking and refresh the list
before selecting again. Linked paths are refused; original files are not changed.

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

App screenshots, screen recording and screen sharing may appear blank because
the app requests secure-window protection. This does not cover a keyboard's
separate fullscreen window or physical observation. Use synthetic examples when
reporting UI problems; never attach real account keys, passwords or live codes.

| Symptom | What to check |
|---------|---------------|
| Loading takes time | Argon2id uses 128 MiB per derivation; performance varies by device |
| Invalid password | Check the entered password; a modified encrypted payload can produce the same failure |
| Unsupported vault format / invalid data | Only the `D2FA` v1 header and explicit integer payload version 1 are accepted; keep the original when unsupported fields or metadata are reported |
| Invalid Base32 | Enter the manual setup key, not a QR image or `otpauth://` URL; check padding and length |
| Duplicate name | Choose a different account name for the new entry |
| `Code unavailable` or rejected code | Check account parameters and the watch clock; no substitute code is generated. If it persists, report the app/device version using synthetic examples, never real keys or codes |
| Old remembered-credential cleanup warning | This version ignores old credentials. Retry cleanup with Lock or a new launch; older versions might still use leftover data. Keep your original vault password |

Report reproducible problems with synthetic data through
[GitHub Issues](https://github.com/wrogistefan/desktop-2fa-watch/issues).
