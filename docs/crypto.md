# Cryptographic Design

This document describes the 0.2.0 implementation. It is not an independent
audit, certification, or promise of compatibility with all future formats.

## Algorithms and parameters

| Parameter | Value |
|-----------|-------|
| KDF | Argon2id, version 19 (1.3), via Bouncy Castle |
| Password encoding | UTF-8, without application-level Unicode normalization |
| Time cost | 4 iterations |
| Memory cost | 131,072 KiB (128 MiB) |
| Parallelism | 2 |
| Derived key | 32 bytes |
| Salt | 16 random bytes per save |
| Encryption | AES-256-GCM through `javax.crypto` |
| Nonce | 12 random bytes per save |
| Authentication tag | 16 bytes |
| AAD | None |

Random salts and nonces are generated with `SecureRandom`. Derivation costs
vary by hardware; no fixed unlock time or real-watch benchmark is claimed.
The core API rejects empty passwords; the UI also rejects whitespace-only input.

## Vault format, version 1

| Offset | Size | Content |
|--------|------|---------|
| 0 | 4 bytes | ASCII `D2FA` |
| 4 | 1 byte | Binary format version `0x01` |
| 5 | 16 bytes | Argon2id salt |
| 21 | 12 bytes | AES-GCM nonce |
| 33 | Variable | Encrypted UTF-8 JSON followed by the 16-byte tag |

The shortest accepted blob is 49 bytes; a valid serialized vault is longer.
The header is parsed separately and is not supplied as GCM AAD. The salt
affects key derivation. There is no separate authenticated-header mechanism.

The decrypted JSON has this shape (example values are synthetic):

```json
{
  "version": 1,
  "entries": [
    {
      "issuer": "GitHub",
      "account_name": "demo@example.com",
      "secret": "JBSWY3DPEHPK3PXP",
      "digits": 6,
      "period": 30,
      "algorithm": "SHA1"
    }
  ]
}
```

`issuer` and `account_name` are nullable. Defaults are encoded; unknown fields
are ignored during loading and are not preserved when the vault is saved.
Binary format version and JSON payload version are separate values, both
currently 1. They do not change with the application version bump to 0.2.0.

## Save and load

Saving serializes and encrypts the data, rejects a result above 10 MiB, writes
a unique temporary file, flushes and syncs it, then replaces `vault.d2fa`.
The implementation requests an atomic move and falls back to a replacement
move if it fails. It copies an existing vault to `.bak` before replacement,
attempts restoration if replacement fails, and removes the backup on success.
It does not automatically recover a leftover backup on the next launch.

Loading checks the file size before reading, validates the header, derives the
key, authenticates/decrypts the payload, and deserializes JSON. The watch uses
a size check followed by `readBytes()` rather than desktop's bounded read.
Both the binary version byte and JSON payload version must be supported.

Authentication/decryption failures are reported as `InvalidPasswordException`;
this cannot distinguish a wrong password from encrypted-payload corruption.
Invalid decrypted JSON raises `CorruptedVaultException`. Entry-level validation
is less strict than desktop's; see [Compatibility](compatibility.md).

## TOTP

The generator uses a time-step counter, HMAC, dynamic truncation, and decimal
padding. SHA1, SHA256, and SHA512 are supported. Intended desktop-compatible
digit counts are 6, 7, and 8; the Kotlin model does not enforce that range.
Unknown algorithms currently fall back to SHA1, and generation failures return
`------`. The UI substitutes a 30-second period for non-positive periods.
These fallbacks are not a validation guarantee.

## References

- [CryptoUtils.kt](../app/src/main/java/org/desktop2fa/desktop/watch/vault/CryptoUtils.kt)
- [Vault.kt](../app/src/main/java/org/desktop2fa/desktop/watch/vault/Vault.kt)
- [TotpGenerator.kt](../app/src/main/java/org/desktop2fa/desktop/watch/vault/TotpGenerator.kt)
- [Desktop cryptographic design](https://github.com/wrogistefan/desktop-2fa/blob/main/docs/crypto.md)
