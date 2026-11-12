# Selected-account package v1 (#31 / E1)

> Historical E1/E2 reference. The owner supplied [transfer specification v0.3](desktop-2fa-transfer-v0.3.md) on 2026-11-12. Its wire schema, strict whole-batch collision rules and durable receipts differ from this proposal. See the [integration status](README.md); the Watch v2 storage design was accepted on 2026-11-12, with implementation and local migration consent still required. Do not combine the two contracts.

Status: **documentation-only proposed contract**. No sender, parser, endpoint,
permission or transfer UI is implemented. Approval of the complete E1–E4 profile,
the required A–D evidence and a separately scoped implementation are required
before receiving real secrets. These wire-schema limits do not change D2FA v1.

## Product boundary

Desktop optionally sends only accounts the user selects. Watch remains a complete
standalone offline authenticator; Desktop remains free and fully functional.
The user previews and explicitly accepts additions on Watch. No whole-vault
replacement, cloud, automatic synchronization, edit/delete, global account IDs
or required permanent Desktop connection is part of this contract. Neither
device sends its vault password or the rest of its vault.

The package is a plaintext application object **only inside the future mutually
authenticated encrypted channel**. JSON is not encryption or authentication.
Do not persist a plaintext package, put it in a QR/clipboard/Intent/saved state,
or send secrets before the peer/session checks in E4. Discovery/pairing metadata
is separate. E4 must define the authenticated envelope and transport; at-rest
D2FA/Argon2id/AES-GCM do not supply transport security.

## Encoding and exact schema

One UTF-8 JSON object, without a BOM or compression. Invalid UTF-8/unpaired
surrogates, duplicate object keys, trailing non-whitespace, unknown keys at any
level and unsupported versions are rejected. Types are exact: an integer token
must not be a string, Boolean, decimal or exponent. No omitted/defaulted fields.

| Object | Required field | Exact value/type and bound |
| --- | --- | --- |
| Root | `format` | String `desktop-2fa.selected-accounts` |
| Root | `version` | Integer literal `1`, independent of D2FA header/payload versions |
| Root | `operation_id` | Canonical lowercase UUIDv4 string, 36 ASCII bytes; fresh CSPRNG-generated ID for a new approved proposal |
| Root | `accounts` | Ordered array of 1–32 account objects; no null elements |
| Account | `issuer` | Null or Unicode string, at most 256 decoded UTF-8 bytes |
| Account | `account_name` | Null or Unicode string, at most 256 decoded UTF-8 bytes |
| Account | `secret` | Nonempty canonical uppercase padded ASCII Base32, at most 512 bytes |
| Account | `algorithm` | Exactly `SHA1`, `SHA256` or `SHA512` |
| Account | `digits` | Integer literal 6, 7 or 8 |
| Account | `period` | Integer literal 1–2,147,483,647 seconds |

The entire decoded application body, including JSON syntax/escapes/whitespace,
must be **at most 65,536 bytes**. This and all field/count limits apply together.
Read at most the limit plus one overflow sentinel; never trust earlier metadata,
a claimed length or a small initial fragment. No decompression expansion exists.
The authenticated framing layer must bound its own buffers/handshake separately.

Null, empty string and nonempty name are distinct values. Preserve valid Unicode
code points and case; do not trim, normalize NFC/NFKC, fold or invent names. Names
must not contain U+0000–U+001F or U+007F; reject rather than silently sanitize the
source. Visually confusing formatting/bidirectional characters require a visible,
unambiguous preview under E3, not identity-by-label or hidden string changes.

For keys, first require the existing strict shared account rules, including
nonempty decoded bytes, ASCII and canonical trailing bits. Only then may the
sender canonicalize accepted case/formatting/padding **in its outgoing copy**.
It must not repair historical empty, Unicode-folded or noncanonical-bit keys to
make a package pass. The original Desktop vault is unchanged. A valid source
outside a package limit is ineligible for this transfer, not truncated or lost.
All six TOTP fields are explicit, preserving decoded key/algorithm/digits/period
and nullable labels; no fallback SHA1/6/30 parameters are substituted.

Use [AccountValidation/SetupKey](../../app/src/main/java/org/desktop2fa/desktop/watch/model/AccountValidation.kt)
and A1/A4/A5 boundary/version/bounded-read rules rather than a second permissive
validator. Parser-specific duplicate-key, exact-token and wire-limit checks are
additional requirements for the later implementation, not existing parser claims.

## Identity, preview and one commit

`operation_id` identifies this proposal for retry handling, never an account or
an authentication credential. Array position identifies an item only within this
proposal. Names/secrets alone are not durable identities. Changed payload or
selection cannot reuse an approved operation silently; E2 defines replay,
collision and acknowledgement behavior. No ID migration of existing records.

After authentication and local manual unlock, validate the **entire** bounded
package before exposing an actionable preview. One invalid/unknown/unsupported
item rejects the package unchanged; silently importing only its valid prefix is
forbidden. Desktop may let the user explicitly deselect ineligible accounts and
create a new proposal, never silently drop them.

Preview every account, including null/empty labels, parameters, count and all
collision/retry decisions. Never display a key or current code merely to confirm
transfer. The user may intentionally skip valid items according to E2, then
approve the exact resulting addition list on Watch. Any edit, stale destination
snapshot, new peer or changed payload invalidates that approval. Skipping all
items means an explicit **no changes** result, not an empty batch write.

The accepted list uses B5's lifecycle-owned
[`VaultSession.submitAdditions`](../../app/src/main/java/org/desktop2fa/desktop/watch/presentation/VaultSession.kt)
and privately copied `AccountAdditionCandidate`: validate fully, compare current
authenticated storage with the preview baseline, then save once. Never call raw
`Vault.save` as a separate receiver writer. A stale preview requires a new preview
and consent; it must not overwrite newer manual additions.

All-or-nothing describes one accepted candidate. B4 outcomes still matter:
NOT_COMMITTED, COMMITTED, COMMITTED_WITH_RECOVERY_ARTIFACTS and UNCERTAIN are not
interchangeable. Do not acknowledge rollback after cancellation or failure if
publication may have happened. E2/E3 define review and lost-acknowledgement states.

## Lifetime, cancellation and errors

Pending plaintext exists only in the active lifecycle-owned receiver session.
E3 supplies an absolute monotonic lifetime and state table; the accepted C2/C3/C4
policy also wins: Lock, screen off/device lock, Activity stop or 60 seconds
without qualifying input clears it. An IME overlay alone does not disable the
deadline. Passive network/I/O activity cannot renew it. A late completion cannot
open a locked vault or restore a draft. Process restart requires new local
authentication and E2 reconciliation, never automatic package restoration.

Before commit, cancellation discards the proposal with no additions. Once the
synchronous writer starts, cancellation is not proof of rollback; retain the
actual B4 outcome and require review as appropriate. No blind retry.

Wire errors are bounded fixed categories such as `INVALID_PACKAGE`,
`LIMIT_EXCEEDED`, `UNSUPPORTED_PACKAGE_VERSION`, `SESSION_CLOSED`,
`STALE_PREVIEW` or `REVIEW_REQUIRED`. A bounded item index may identify a failed
entry after authentication. Never echo keys, passwords, URIs, generated codes,
raw provider exceptions, package bytes or secret-derived fingerprints in errors,
logs, notifications or acknowledgements.

## Versioning, existing users and review cases

Negotiate only an explicitly supported package version before receiving secrets;
an untrusted offer cannot raise local ceilings. Future fields/types/semantics or
limits require a separately specified version/negotiation decision. Unsupported
data is rejected, not coerced into v1. Package versioning neither changes nor
proves compatibility of internal vaults. No migration/reset/replacement follows
from package failure. Whole-vault backup/import remains unchanged [issue #10](https://github.com/wrogistefan/desktop-2fa-watch/issues/10).

[Synthetic v1 example](examples/selected-accounts-v1.json): all data and IDs are
public test material, never actual accounts. The limits are a conservative design
budget, not physical-watch performance measurements (#30/#37 remain required).

| Synthetic case | Required result |
| --- | --- |
| Example with Unicode and null labels | Preview exact labels/parameters; commit only after explicit Watch consent |
| 32 versus 33 otherwise valid items | Accept count boundary / reject entire over-limit proposal |
| 65,536 versus 65,537 serialized bytes (valid JSON may have padding whitespace) | Accept byte boundary / bounded rejection without any write |
| Label of 128 `Ż` versus 129 `Ż` | 256-byte boundary / reject 258 decoded UTF-8 bytes |
| Canonical key of 512 versus 520 ASCII bytes | Accept key bound / reject without truncation |
| Null versus empty labels; identical labels/secrets | Preserve values; require E2 collision decisions, no first-match selection |
| Missing field, extra field, duplicate key, version 2 or string `"1"` | Reject unchanged; no fallback/default or metadata stripping |
| Empty/Unicode-folded/noncanonical-bit key, 10 digits, period 0 or unknown algorithm | Reject the entire package; preserve source and destination |
| Late approval after Lock/expiry or destination changed after preview | Stay closed or re-preview; no stale commit |
| Cancellation before/after publication or lost acknowledgement | Use actual B4/E2 state; never infer rollback or silently repeat |

Verification for this documentation task: schema/example/source and scenario
review, JSON syntax/field checks and `git diff --check`. No parser, protocol,
Gradle/device test, security audit or implemented transfer is claimed.
