# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - Unreleased

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
