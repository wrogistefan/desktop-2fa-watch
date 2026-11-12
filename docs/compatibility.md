# Desktop Vault Compatibility

The owner-provided [transfer v0.3 profile](transfer/desktop-2fa-transfer-v0.3.md) is separate from vault-file compatibility and replaces the proposed E1/E2 wire/collision direction for future implementation. Its durable receipts require the [pending Watch v2 storage decision](transfer/vault-v2-proposal.md). Existing v1 files and implementation remain unchanged.

The current reference is Desktop-2FA **v0.10.0**, commit
[`7d1089d6bee0f3f82678ca8a13988c742840fba0`](https://github.com/wrogistefan/desktop-2fa/tree/7d1089d6bee0f3f82678ca8a13988c742840fba0).
This supersedes the old f2baf74 source comparison. The
[immutable synthetic corpus](../app/src/test/resources/interop/README.md) is
generated with that release's actual models, Vault writer and TOTP generator.
The D2 #29 runner executes both implementations against that corpus; see
[execution evidence](testing/interop-regressions.md). This is not compatibility certification.

## Current parity

| Layer | Status |
|-------|--------|
| Binary layout | Same `D2FA`, `01`, 16-byte salt, 12-byte nonce, ciphertext, 16-byte tag |
| KDF | Same Argon2id v1.3 parameters and UTF-8 password encoding |
| Encryption | AES-256-GCM without AAD on both sides |
| JSON | Same `version`, `entries`, and TOTP field names, including `account_name` |
| TOTP | Both implement SHA1/SHA256/SHA512 and parameterized digits/periods |
| Watch transfer UI | Not implemented |
| Automated Python ↔ Kotlin round trip | Pinned synthetic runner; see execution evidence below |

For valid v1 data, the implementations are expected to read each other's
encrypted files given the correct password. The application version 0.2.0 does
not introduce a new vault format or require migration from watch 0.1.0.

The historical emulator regression test loaded a Python-generated encrypted fixture with
synthetic duplicate and unnamed entries. It exercised the layout and crypto
primitives, but did not run the desktop `Vault` implementation or verify a
complete desktop → watch → desktop round trip.

The current runner separately loads the pinned Python fixture in Kotlin, saves
it with Kotlin and reads it using the actual pinned Desktop Vault. It also
creates a fresh Kotlin vault, loads/re-saves it in Python and loads that result
in Kotlin. All fields, order, duplicate/null names and original key text must
match; codes are compared at fixed timestamps using both generators. Negative
cases follow the manifest's explicit differences and require unchanged source
hashes. Detailed commands/results and limits are in the execution report.

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
Python-library interoperability. The larger pinned corpus is now in #28;
cross-language execution is provided by the #29 runner.

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

Name lookup now matches the pinned Desktop 0.10.0 resolution tiers (#35): exact
case-sensitive `account_name` matches take precedence over issuer fallback. More
than one match at the selected tier throws `AmbiguousEntryException` (an
`IllegalArgumentException`); it never chooses by order or falls back from an
ambiguous account name to an issuer. Missing matches still throw
`IllegalArgumentException`, with a generic message. `removeEntry` resolves first
and changes nothing on ambiguity/missing selection. Unique callers keep their
signatures/behavior. `findEntries` remains an enumeration of both labels, not an
implicit selector. Historical duplicate/null/empty records still load/display;
there is no format/ID migration or edit/remove UI. Future additive transfer uses
E2 explicit collision/operation semantics and must not use first-match lookup.

The [E2 collision/retry policy](transfer/collisions-retry.md) preserves existing
accounts and uses explicit Watch choices instead of first-match lookup. Its
owner-accepted memory-only direction requires manual reconciliation after Lock,
restart or expiry. Communication/transfer remains a separate later design. It adds no
v1 fields, sidecar, backup change or account-ID migration and does not promise
exactly-once delivery across process death.

The [selected-account package v1 proposal](transfer/package-v1.md) is a bounded,
additive application contract, separate from D2FA v1 and any transport. It carries
only user-selected explicit parameters, preserves nullable names and requires
Watch preview/consent before one batch commit. It cannot replace the local vault
or exchange vault passwords. Unsupported packages leave both vaults unchanged.
The proposal adds no implementation/network permission. Collision/retry,
receiver states and authenticated transport remain E2–E4 decisions; approval and
all required evidence are gates before any later implementation. This direction
does not close or redefine [whole-vault issue #10](https://github.com/wrogistefan/desktop-2fa-watch/issues/10).

The D1 corpus records explicit supported/rejected cases and complete fields,
including historical unsupported keys and the missing-version difference. D1
hashes/provenance alone do not prove a round trip; D2 executes the libraries. Binary
vault compatibility and the future additive account-package contract are
separate: identical internal vault storage is not required for selected-account
transfer. This work implements no import/export UI and does not close #10.

The #25 session change does not alter encrypted v1 files, TOTP parameters or
password encoding. Supported files need no migration. Existing users must supply
their original vault password: Remember/cache unlock is removed and legacy
preferences are scheduled for cleanup. Keystore loss/cleanup errors never reset
the vault; leftover cache is ignored by this version. See the
[session transition and recovery limits](session-policy.md).

Restart recovery does not reinterpret storage as a transfer package. Leftover
backup/fixed/legacy pending files require review; a selected supported file can
be authenticated for read-only code access without promotion or deletion. There
is no automatic newest-copy selection or writable repair. Supported v1 data needs
no migration, and unsupported originals stay intact. Future transfer must refuse
read-only recovery sessions. See [recovery checks](testing/recovery-regressions.md).
Regular supported v1 files remain unchanged. Watch now refuses final-component
symlinks on load and changed recovery selections; this is a filesystem policy,
not a new encrypted format or a transfer-package identity requirement.

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
export. An imported file's password, invalidated session and legacy credential
cleanup must be handled consistently after replacement.

Automatic sync and account merging are outside that initial scope. The model
has no stable IDs, edit timestamps, or deletion records for resolving conflicts.
Do not independently reduce watch KDF parameters under the existing version
byte: those parameters are implicit in the v1 format.
