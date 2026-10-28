# Desktop-2FA for Wear OS User Manual

This manual covers the 0.2.0 MVP. The app runs independently on a Wear OS watch
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

## Unlock an existing vault

If no usable cached password is available, **Unlock Vault** prompts for the
master password. The same remember option controls whether it is cached.
A failed cache lookup does not replace or reset the vault.

Unlocking a vault does not reapply the creation strength policy. The password
must match the one used to encrypt it. The UI rejects blank password input.

## Add an account

Scroll to **Add Account** and fill in all three fields:

| Field | What to enter |
|-------|---------------|
| Issuer | Service name, for example `GitHub` |
| Account Name | A unique name, for example `demo@example.com` |
| Secret (Base32) | The service's manual setup key, without spaces |

The form trims and uppercases the secret, accepts `A–Z`, `2–7`, and trailing
`=` padding, and requires a length divisible by 8. For example,
`JBSWY3DPEHPK3PXP` is synthetic test data, not a key for a real account.
The form's check is not a complete Base32 decoder validation.

Tap **Save Account** (scroll down if necessary). On success, the app saves and
reloads the vault, returns to the account list, and displays a confirmation
near the actions at the end of the list. New accounts use SHA1, 6 digits, and
a 30-second period; there is no parameter editor in this version.

QR scanning, `otpauth://` entry, renaming, deleting, password changes, and
import/export are not available in the current UI.

## Read codes

Each card shows the issuer, account name, code, and seconds remaining.
The list refreshes its time value approximately once per second while visible.
Codes depend on the watch clock; check date/time synchronization if a service
rejects them. `------` indicates code generation failed.

Duplicate account names and unnamed entries already present in a loaded vault
can be displayed. Adding a new entry with an existing account name is rejected.

## Lock the vault

Scroll to **Lock Vault**. The app immediately leaves the account view, clears
its active vault reference and form inputs, and requests removal of the cached
password in the background. This does not delete the encrypted vault.

There is no automatic inactivity lock. Returning to the watch face is not an
explicit lock action. Clearing references does not guarantee memory erasure;
see the [security model](SECURITY.md).

## Storage, updates, and recovery

The vault is stored as `vault.d2fa` in private app storage. A normal update with
the same app ID and signing key preserves it. Uninstalling, clearing app data,
or resetting the watch can remove it. Configured Android backup/transfer rules
exclude the vault and credential preferences. The temporary `.bak` used during
saving is not a user backup or a supported restore mechanism.

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
| Unsupported vault format | Only the `D2FA` v1 header and payload version 1 are accepted |
| Invalid Base32 | Enter the manual setup key, not a QR image or `otpauth://` URL; check padding and length |
| Duplicate name | Choose a different account name for the new entry |
| `------` or rejected code | Check the key, parameters, and watch clock; invalid loaded entries are not fully validated yet |
| Remember setting does not persist | Protected preference creation or saving may have failed; unlock manually |

Report reproducible problems with synthetic data through
[GitHub Issues](https://github.com/wrogistefan/desktop-2fa-watch/issues).
