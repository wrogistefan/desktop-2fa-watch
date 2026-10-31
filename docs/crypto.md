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
at the root or entry level are rejected during loading rather than silently
removed on a later save. No automatic format migration is performed.
Binary format version and JSON payload version are separate values, both
currently 1. They do not change with the application version bump to 0.2.0.
Both must be explicit and supported: the header byte is `0x01`, and the JSON
`version` is the integer literal `1`. Missing/null, quoted, Boolean, decimal,
exponent or out-of-range payload versions are rejected without coercion.
Other representable integer versions are unsupported. The serializer always
emits `version`, even when other defaults are omitted; the vault writer refuses
unsupported model versions before any write or KDF.

Normal Watch/Desktop v1 writers and the pinned historical fixture already emit
the version field, so supported generated files require no migration. Previously
accepted unversioned/mistyped JSON is now rejected intact. There is no automatic
guess, repair or metadata-stripping path. Unknown root/entry fields are also
rejected before a writable vault is exposed, rather than discarded on save.

## Save and load

New-vault setup uses `saveNew`, separate from updating an existing vault. It
inspects the directory, target and possible `.bak` with error-reporting metadata
reads. Only confirmed absence allows creation. The encrypted
file is written through a channel opened with atomic `CREATE_NEW`; an existing
destination always causes failure, even if it appeared after the inspection.
The content is fully encrypted before creation and the completed file is synced.
If creation/write/sync fails, an incomplete or uncertain new file may remain;
it is not removed or treated as permission to run setup again. This prevents
replacement but does not make the entire new-file write atomic or claim
directory-sync/power-loss durability. Android's app sandbox can deny hard links,
so creation does not depend on them.

Startup inspection errors show a retry screen; a backup without the main file
blocks creation pending recovery. Backups are not automatically promoted or
deleted. A failed password/authentication/load never authorizes new setup.
The startup resolver keeps inspection and cached-unlock outcomes separate: an
unavailable cache or rejected load falls back to manual unlock, while an inspection
error stays on retry. Cancellation propagates without producing a navigation result.
The create-only protection was introduced in #38; #19 tests its startup integration.

Updating requires an existing regular file. It serializes/encrypts the data,
rejects a result above 10 MiB, opens `vault.d2fa.tmp` with `CREATE_NEW`, writes,
flushes, syncs and closes it. It copies the old destination to `vault.d2fa.bak`
without overwriting an existing backup, then syncs the completed backup.
Publication requires `ATOMIC_MOVE`; there is no non-atomic fallback, including
when atomic moves are unsupported. Access/I/O errors are not a reason to weaken
replacement semantics. Successful return from the move is the commit point.

Failures retain any temporary/backup candidates; no automatic copy-back can
overwrite a possibly committed destination. Existing artifacts block another
update rather than being silently replaced. After a successful move, backup
cleanup failure returns an internal committed-with-artifacts result, preserving
the committed main file. The current public `save` API still returns Unit;
UI-facing reconciliation of uncertain/post-commit outcomes is separate (#22).
Conservative next-start recovery/cleanup remains #21. The format, password and
KDF are unchanged. Backup/transfer rules exclude the fixed temporary path too.

Android [Files.move](https://developer.android.com/reference/java/nio/file/Files#move(java.nio.file.Path,%20java.nio.file.Path,%20java.nio.file.CopyOption...))
specifies undefined file state after a failed non-atomic move, motivating this
fail-closed choice. Atomic replacement support is verified on disposable emulator
storage; unsupported devices fail safely instead of falling back. File syncing
does not establish directory-entry or physical power-loss durability. No hardware
power-cut result is claimed; see [replacement regression checks](testing/replacement-regressions.md).

Loading may reject an oversized metadata hint early, then reads through a bounded
stream and closes it on success/failure. It consumes at most 10 MiB plus one
overflow sentinel byte, rejecting overflow before KDF/decryption even when the
file grows after the initial size check. Only a complete, accepted read proceeds
to header validation, key derivation, authenticated decryption and JSON parsing.
Both the binary version byte and JSON payload version must be supported.

Authentication/decryption failures are reported as `InvalidPasswordException`;
this cannot distinguish a wrong password from encrypted-payload corruption.
Invalid or unknown-field JSON raises `CorruptedVaultException`. Account validation
also runs after decoding, before exposing a writable vault, and before saving an
entry-list snapshot. Invalid entries never cause an empty replacement vault.

## Account validation

The shared `model/SetupKey` and `model/AccountValidation` rules apply to loaded
entries, manual additions, the wizard and saved snapshots. Secrets use RFC 4648
ASCII Base32 (letters A–Z/a–z, digits 2–7), with correctly supplied padding or
omitted padding. ASCII space and U+0009–U+000D formatting whitespace are ignored
for validation/decoding. Unicode folding, other whitespace, invalid padding,
non-zero trailing bits and empty keys are rejected. Errors do not echo key data.

Known algorithms are exactly SHA1/SHA256/SHA512, digits are 6/7/8 and the period
is a positive Kotlin Int (up to 2,147,483,647 seconds). Nullable names and legacy
duplicate labels are valid stored data; new-name collision checks remain separate.
New additions store canonical uppercase padded keys. Loading/saving valid stored
entries preserves their original key text and all account parameters. Generation
canonicalizes only a temporary decoding value, never the stored entry.

Historical empty/non-generatable keys are rejected with the original file intact;
there is no automatic repair, silent deletion or format migration. See
[Compatibility](compatibility.md) for intentional differences from Desktop.

## TOTP

The generator uses a time-step counter, HMAC, dynamic truncation, and decimal
padding. SHA1, SHA256, and SHA512 are supported. Intended desktop-compatible
digit counts are 6, 7, and 8; vault boundaries enforce that range.
Generation independently applies the shared account rules and rejects negative
Unix times. Unknown algorithms, invalid digits/periods/keys and provider failures
raise a controlled `TotpGenerationException`, without key material or raw provider
diagnostics. There is no algorithm/period substitution or `------` placeholder.
The account card shows `Code unavailable` and no countdown on failure; other
valid cards still render. Successful countdowns use the account's actual period.
The default SHA1/6-digit/30-second arguments for newly created accounts remain
unchanged; they are not fallbacks for invalid supplied data.

Targeted fixed-time cases use public vectors from
[RFC 6238 Appendix B](https://www.rfc-editor.org/rfc/rfc6238.html#appendix-B).
The JVM suite covers all 18 published eight-digit results and their 6/7-digit
decimal suffixes, plus 162 independently generated boundary/parameter vectors.
Controlled countdown tests cover rollover without sleeps. Ten-digit generation
remains unsupported; these checks do not establish hardware/lifecycle behavior
or complete cross-platform vault interoperability.

## References

- [CryptoUtils.kt](../app/src/main/java/org/desktop2fa/desktop/watch/vault/CryptoUtils.kt)
- [Vault.kt](../app/src/main/java/org/desktop2fa/desktop/watch/vault/Vault.kt)
- [TotpGenerator.kt](../app/src/main/java/org/desktop2fa/desktop/watch/vault/TotpGenerator.kt)
- [Desktop cryptographic design](https://github.com/wrogistefan/desktop-2fa/blob/main/docs/crypto.md)
