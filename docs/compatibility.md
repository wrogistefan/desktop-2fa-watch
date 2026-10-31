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

- Watch now validates Base32, digits 6/7/8, positive Int periods and known
  algorithms at load/add/save boundaries. Models are serializable data, not
  independently validated constructors; future importers must use the same boundary.
- Watch generation rejects invalid supplied parameters with a controlled error;
  the list shows no code or countdown for that entry. It never substitutes SHA1
  or a 30-second period. Default parameters for new manual accounts are unchanged.
- Watch requires an explicit integer-literal JSON payload version 1, independently
  of binary header v1. The historical desktop revision compared above had looser
  version acceptance; the current 0.10.0 differences are described below.
- Both have a 10 MiB load limit with bounded reads. Watch now enforces the bound
  on bytes actually consumed, independent of earlier file metadata, and also
  limits encrypted output size on save.
- Unknown JSON fields are rejected by watch before exposing a writable vault.
  Existing files with additional metadata remain unchanged but cannot be opened
  by this version; use a reader supporting those fields. Do not strip metadata
  from the original file to work around rejection.
- Duplicate names may exist in older desktop vaults. Watch renders them safely;
  new entries still require unique account names.
- Watch creation uses a character/length password heuristic; desktop uses
  zxcvbn in applicable flows. A similar score is not policy equivalence.

## Current account rules and historical records (#14)

The account-validation comparison uses Desktop-2FA 0.10.0 pinned to
[`7d1089d6bee0f3f82678ca8a13988c742840fba0`](https://github.com/wrogistefan/desktop-2fa/tree/7d1089d6bee0f3f82678ca8a13988c742840fba0),
specifically `utils/base32.py` and `vault/models.py`. This is a source comparison
and targeted synthetic Kotlin regression coverage. One immutable Python-written
v0.8.3 fixture from that commit is hash-checked and loaded/re-saved to verify all
fields, Unicode labels/password and nullable names. It is not full current
Python-library interoperability; the larger corpus/runner stays in #28/#29.

Watch requires non-empty ASCII Base32 and canonical trailing bits at every vault
boundary. It accepts ASCII space plus U+0009–U+000D formatting whitespace; pinned
Desktop's decoder ignores only ASCII spaces and can accept non-canonical trailing
bits. Desktop can retain some historical empty or Unicode-folded records for
listing/repair. Watch intentionally rejects those vaults intact rather than
silently changing/removing keys or generating a code from a substituted value.
Use a reader supporting the original records to recover/review them; keep the
original file and service recovery methods. There is no automatic Watch repair.

Valid stored key text, Unicode labels, nullable issuer/account_name, ordering and
historical duplicate labels remain unchanged on load/save. New manual accounts
still require unique names; this is not a transfer collision/identity policy.
The wizard uses the shared validator and canonicalizes new keys only. Supported
v1 vaults require no migration. Existing callers of `addEntry` keep their signature.

## Version and extension policy (#17)

Watch rejects unknown root and entry fields (including nested extension objects)
before exposing a writable vault. They cannot silently disappear during a later
manual addition. A higher version is not a request to reinterpret data as v1.

Header byte `0x01` and an explicitly present JSON integer literal `1` are required.
Missing/null, string, Boolean, fractional/exponent and out-of-range values fail
as invalid data; other Int versions fail as unsupported. Unsupported models also
cannot be saved over existing files or used to create new ones. Errors preserve
original encrypted bytes and never authorize a fresh setup.

Pinned Desktop 0.10.0 rejects unknown fields and mistyped provided versions, but
its model can default a missing version to 1. Watch intentionally does not guess
that version. Normal generated v1 files, including the immutable Python fixture,
already have explicit version 1 and need no migration. For an unversioned legacy
file, keep the original and use a compatible reader to inspect/explicitly save a
separate supported copy; no automatic Watch repair or lossy conversion is added.

## Import/export plan

Restart recovery does not reinterpret storage as a transfer package. Leftover
backup/fixed/legacy pending files require review; a selected supported file can
be authenticated for read-only code access without promotion or deletion. There
is no automatic newest-copy selection or writable repair. Supported v1 data needs
no migration, and unsupported originals stay intact. Future transfer must refuse
read-only recovery sessions. See [recovery checks](testing/recovery-regressions.md).

The #16 JVM suite verifies all RFC 6238 Appendix B timestamps for all three hash
algorithms, supported digit counts/leading zeroes, non-default periods, time zero,
large counters/timestamps and countdown rollover. Additional expected values come
from an offline Python standard-library oracle checked against the published RFC
table. This checks TOTP semantics, not the complete current Desktop Vault library
or Android lifecycle/clock behavior on a physical device.

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
