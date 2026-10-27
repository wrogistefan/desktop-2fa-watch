# desktop-2fa for Wear OS

Offline TOTP Authenticator for Wear OS, fully compatible with the desktop-2fa vault specification.

## Features & Architecture

- **Encrypted Vault Storage (`.d2fa`):** 
  - 100% compatible with desktop-2fa binary file format (`D2FA\x01` header + 16-byte salt + AES-256-GCM encrypted payload).
- **Strong Cryptography:**
  - **Argon2id** key derivation (`t=4`, `m=128MB`, `p=2`) using Bouncy Castle.
  - **AES-256-GCM** authenticated encryption (`12-byte nonce` + `ciphertext` + `16-byte auth tag`).
- **Data Models:**
  - `TotpEntry` and `VaultData` matching the desktop Pydantic schema (`account_name`).
- **Reliability & Security:**
  - Atomic file replacements with `.bak` backup recovery.
  - Strict size bounds (`MAX_VAULT_SIZE = 10 MB`).
  - Thread-safe key derivation and persistence.

## Build & Test

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## License

Apache-2.0
