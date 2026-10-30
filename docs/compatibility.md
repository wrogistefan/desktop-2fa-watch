# Desktop Vault Compatibility

The watch 0.2.0 source was compared with
[`desktop-2fa@f2baf74`](https://github.com/wrogistefan/desktop-2fa/tree/f2baf741cef4799a4983577f76fd866c71496816).
This is a source-level assessment, not completed interoperability certification.

## Current parity

| Layer | Status |
|-------|--------|
| Binary layout | Same `D2FA`, `01`, 16-byte salt, 12-byte nonce, ciphertext, 16-byte tag |
| KDF | Same Argon2id v1.3 parameters and UTF-8 password encoding |
| Encryption | AES-256-GCM without AAD on both sides |
| JSON | Same `version`, `entries`, and TOTP field names, including `account_name` |
| TOTP | Both implement SHA1/SHA256/SHA512 and parameterized digits/periods |
| Watch transfer UI | Not implemented |
| Automated Python ↔ Kotlin round trip | Not implemented |

For valid v1 data, the implementations are expected to read each other's
encrypted files given the correct password. The application version 0.2.0 does
not introduce a new vault format or require migration from watch 0.1.0.

The emulator regression test loaded a Python-generated encrypted fixture with
synthetic duplicate and unnamed entries. It exercised the layout and crypto
primitives, but did not run the desktop `Vault` implementation or verify a
complete desktop → watch → desktop round trip.

## Differences that matter

- Desktop validates Base32, digits 6/7/8, positive periods, and known algorithms
  through Pydantic. Watch deserialization does not enforce equivalent constraints.
- Watch generation falls back to SHA1 for unknown algorithms; the current list
  replaces non-positive periods with 30. Invalid data may produce misleading codes.
- Watch requires JSON payload version 1. The compared desktop model accepts an
  integer without the same load-time version check.
- Both have a 10 MiB load limit, but watch checks size before `readBytes()` while
  desktop uses a bounded read. Watch also limits serialized output on save.
- Unknown JSON fields are rejected by watch before exposing a writable vault.
  Existing files with additional metadata remain unchanged but cannot be opened
  by this version; use a reader supporting those fields. Do not strip metadata
  from the original file to work around rejection.
- Duplicate names may exist in older desktop vaults. Watch renders them safely;
  new entries still require unique account names.
- Watch creation uses a character/length password heuristic; desktop uses
  zxcvbn in applicable flows. A similar score is not policy equivalence.

## Import/export plan

The changes for #4/#5/#6 keep the binary/payload v1 layout, KDF, nullable names
and historical duplicate records. Ordinary supported v1 vaults need no migration.
Stricter unknown-field rejection intentionally replaces the previous lossy
acceptance. The comparison above remains historical, not a test of current
Desktop-2FA 0.10.0; current fixtures/interoperability are tracked in #28/#29.

The additive selected-account direction is tracked in [roadmap #13](https://github.com/wrogistefan/desktop-2fa-watch/issues/13).
It differs from #10's whole-vault import/export scope below, which remains unchanged.

[Issue #10](https://github.com/wrogistefan/desktop-2fa-watch/issues/10) defines the
follow-up work on a separate branch:

1. Align entry validation and document version/unknown-field handling.
2. Establish fixtures and tests in both directions, including Unicode passwords,
   optional fields, algorithms, digit counts, periods, invalid data, and tampering.
3. Compare generated codes for fixed timestamps.
4. Implement encrypted whole-vault export and validated import, with explicit
   replacement confirmation and preservation of the old vault on failure.
5. Measure KDF costs on a physical watch and select a practical transfer channel.

Desktop already has encrypted whole-vault import/export commands. The first
watch workflow should use the same v1 file, without an intermediate plaintext
export. An imported file's password and the device's cached credentials must
be handled consistently after replacement.

Automatic sync and account merging are outside that initial scope. The model
has no stable IDs, edit timestamps, or deletion records for resolving conflicts.
Do not independently reduce watch KDF parameters under the existing version
byte: those parameters are implicit in the v1 format.
