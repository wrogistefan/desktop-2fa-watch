# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
