# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### ✨ Changed

- Replace the account form with a three-step wizard: service name, account name,
  and setup key, each with a heading and one input per screen.
- Add Back/Cancel, secret visibility, keyboard submission, and validation after
  keyboard dismissal. Disable autofill and request no suggestions.
- Accept whitespace-formatted and unpadded Base32 keys, validate decoding and
  trailing bits, and store canonical padding for desktop-compatible readers.
- Guard repeated saves, retain drafts on errors, and scroll to the new account
  with confirmation on its card.

### 🧪 Testing

- Verify all RFC 6238 vectors for three algorithms, 6/7/8 digits, leading zeroes,
  independent boundary/large-counter cases and controlled countdown rollover (#16).
- Cover Base32 normalization/padding, invalid input, wizard navigation, duplicate
  names, save claiming, and retries with unit tests.
- Validate wizard entry, native Samsung keyboard actions, draft retention,
  duplicate-name checks, normalization, save/cancel, legacy entries, and locking
  on a physical Galaxy Watch Ultra; add screenshots and a device report.
- Samsung's fullscreen editor still hides the wizard heading despite IME hints.

### 🐛 Fixed

- Detect backup/fixed/legacy pending writes at startup and offer authenticated
  read-only code recovery without replacing or deleting any candidate (#21).
- Require atomic existing-vault replacement without a non-atomic fallback or
  destructive restore; retain failed-write evidence and test I/O faults (#20).
- Keep startup routing independently testable: only confirmed absence allows
  setup; cache/load failures require unlock and cancellation is propagated (#19).
- Enforce the vault read limit on bytes actually consumed, including files that
  grow after the size check; close streams and reject partial/oversized input (#18).
- Require explicit integer payload version 1 and reject unsupported write
  snapshots; preserve rejected version/extension data without silent migration (#17).
- Report controlled TOTP generation failures and render an unavailable state
  without a code/countdown, instead of substituting SHA1/30 seconds or dashes (#15).
- Share ASCII Base32 and TOTP parameter validation across wizard/load/add/save,
  reject invalid snapshots before I/O, and preserve valid historical key text,
  nullable names and duplicate labels (#14).
- Surface password-cache write and deletion failures, preserve immediate Lock,
  and clear unreadable secure/legacy stores without decrypting them (#4).
- Refuse setup on uncertain storage or leftover recovery backups, and publish
  new vaults without replacing a competing existing file (#5).
- Reject unknown vault/entry JSON fields before modification instead of losing
  them on save; supported v1 vaults need no migration (#6).
- Keep input text readable on the dark wizard background in error/disabled states.

## [0.2.0] - 2026-10-28

### 🆕 Added

- Wear OS MVP workflow: create and unlock a vault, manually add TOTP accounts,
  display live codes and countdowns, and lock the vault.
- Optional password storage in encrypted preferences protected by Android Keystore.
- Native Wear OS `TransformingLazyColumn` account list with rounded cards.
- User, security, cryptography, UX, compatibility, and development documentation,
  with emulator screenshots captured using synthetic accounts.

### 🐛 Fixed

- List keys no longer collide for duplicate account names or secrets, including
  unnamed entries loaded from legacy vaults.
- List header and footer now each have a single layout container, keeping the
  title and Add Account / Lock Vault actions visible and reachable.
- Non-positive TOTP periods use a 30-second UI fallback to avoid division by zero.
  Strict validation remains planned in issue #10.

### 🛡️ Security

- Lock clears active UI state and requests credential-cache removal off the main
  thread; plaintext fallback credential stores are removed when clearing.
- Credential preference initialization handles unavailable/corrupted storage and
  attempts preference recreation before falling back to manual unlock.
- Android backup/transfer rules exclude the vault and credential preferences.

### 📦 Versioning

- Bump `versionName` from `0.1.0` to `0.2.0` and `versionCode` from 2 to 3.
  The minor increment reflects new MVP functionality during pre-1.0 development.
- The `.d2fa` binary format and JSON payload version remain 1.
- Full desktop interoperability verification and watch import/export are deferred
  to [issue #10](https://github.com/wrogistefan/desktop-2fa-watch/issues/10).

## [0.1.0] - 2025-02-19

### Added
- Core encrypted vault storage matching desktop-2fa binary file format (`D2FA` magic header + version `0x01` + 16-byte salt).
- Argon2id password-based key derivation (`t=4`, `m=128MiB`, `p=2`) via Bouncy Castle.
- AES-256-GCM authenticated encryption and decryption utilities.
- Pydantic-compatible `TotpEntry` and `VaultData` models with `kotlinx.serialization`.
- Vault lifecycle management (create, load, save, addEntry, getEntry, removeEntry) with atomic file updates and `.bak` recovery.
- Robust error handling hierarchy (`InvalidPasswordException`, `CorruptedVaultException`, `UnsupportedFormatException`, `VaultIOException`).
- Unit test suite (`VaultTest`) covering persistence, crypto, and versioning.
- GitHub Actions CI pipeline (`Android CI`) for automated testing and building.
- Initial Wear OS UI integration in `MainActivity` with vault initialization action.
