# Watch vault v2: accepted format, migration and recovery design

Status: **DESIGN ACCEPTED; CODEC IMPLEMENTED; MIGRATION/TRANSFER PENDING**, 2026-11-12.
After reviewing why durable receipts require a new version and how this affects
Desktop, the owner approved v2 and required explicit, precise documentation.
This records the accepted design; implementation evidence is tracked separately
and does not itself establish release readiness.
The imported [transfer specification v0.3](desktop-2fa-transfer-v0.3.md),
especially Sections 7–8, requires durable encrypted receipts. This supersedes
the old E2 memory-only direction for the newly authorized transfer work.
Approval to develop migration does not replace each end user's explicit consent
on their own watch before any existing vault is migrated.

## Accepted decision

Implement a Watch-specific v2 vault reader/writer and explicit, one-time opt-in
migration before enabling transfer. Ordinary v1 use continues to write v1.
Desktop does not need to implement this storage format to implement transfer:
its wire contract is v1 in the supplied specification. Do not label this as a
shared Desktop vault v2 or change Desktop files automatically.

## Exact accepted format

Keep the `vault.d2fa` location and existing 10 MiB limit. The envelope is
`D2FA || 0x02 || salt[16] || nonce[12] || ciphertext || tag[16]`.
Argon2id v19, 4 iterations, 131072 KiB, parallelism 2, 32-byte key,
UTF-8 password and AES-256-GCM remain unchanged. Every save generates a fresh
salt and nonce. For v2, authenticate the exact five header bytes as GCM AAD;
v1 continues to use no AAD. Header AAD is an explicit v2 design choice,
not a requirement of durable receipts or of the network protocol. Readers dispatch only supported header versions,
then require the matching explicit integer payload version after authentication.
An unknown version is never retried as another version or as a new empty vault.

The v2 root contains exactly `version: 2`, `entries` and `transfer_receipts`.
Entries retain all current v1 fields and validation, including historical
nullable labels, duplicate names and seven-digit records. The stricter transfer
profile applies to incoming records, not retroactively to stored accounts.
No permanent account IDs are introduced.

`transfer_receipts` is an array ordered oldest to newest, maximum 128 elements.
Each receipt contains exactly:

| Field | Validation |
| --- | --- |
| `transfer_id` | 32 lowercase hexadecimal characters; unique across retained receipts |
| `offer_sha256` | 64 lowercase hexadecimal characters |
| `added_item_ids` | Unique 32-character lowercase hex IDs |
| `skipped_item_ids` | Unique 32-character lowercase hex IDs, disjoint from added IDs |

The combined ID count is 1–50. Preserve OFFER order within each ID list.
Append a receipt only in the same transaction as the approved additions;
evict the oldest if necessary. All-duplicate approval writes a receipt with an
empty added list. No timestamps, names, secrets, PSKs, pending batches or
plaintext journal are stored in receipts. Reject duplicate JSON keys, unknown
fields, invalid UTF-8 and malformed types, rather than silently repairing them.
Normal v2 manual additions preserve the complete receipt list.

## Migration and user consent

The Receive entry point first explains that enabling transfer upgrades local
storage, older Watch/Desktop versions cannot read it, and an old safety copy
does not contain later accounts. The user can cancel with zero writes.
Migration occurs separately from the first OFFER: no account is imported by
format consent. The original password remains required for new sessions.

Under the existing process-wide writer mutex, reload/authenticate v1 and check
the exact baseline. Refuse migration from read-only recovery, with unresolved
artifacts, unknown fields, invalid accounts or another pending write. Serialize
the same entries into v2 with an empty receipt array; do not normalize legacy
key text or labels. Preflight the encrypted size before publication.

Before replacing v1, create an encrypted byte-for-byte safety copy at the fixed
path `noBackupFilesDir/transfer-migration/vault-v1.d2fa`, using exclusive create,
complete bounded write and file sync. An existing or partial copy blocks the
operation and opens storage review; never overwrite or automatically remove it.
Verify its byte count and hash locally against the authenticated source. Keep
the hash in memory only. The copy retains the original salt, nonce and password.
It is sensitive encrypted data and must never be attached to reports.

Publish v2 using the existing temporary/backup and atomic-replacement machinery,
with v2-specific verification. Success requires reloading the entire v2 snapshot
and confirming all original entries and empty receipts. Migration cancellation
before the save boundary discards the proposal. Once saving starts, Lock hides
data immediately and the transaction finishes without rollback, using existing
generation ownership and non-cancellable I/O rules.

Do not advertise power-loss durability from the existing file-sync tests. Before
transfer release, demonstrate the spec's restart invariant and evaluate directory
sync on supported Android storage. Unsupported safe publication blocks transfer.

## Recovery and uncertain outcomes

| Observed state | Required action |
| --- | --- |
| Wrong password, invalid v1 or invalid v2 | Leave every file intact; no setup/reset authorization |
| Safety-copy failure before publication | Original v1 stays authoritative; block migration and show storage review |
| Clean v2 reload after migration | Continue password-only operation; enable transfer only after its other gates pass |
| v1 main plus backup/temp after interruption | Existing read-only recovery; no automatic promotion or retry |
| v2 main plus backup/temp after interruption | Same conservative read-only review; account and receipt snapshots are inseparable |
| Publication exception | Treat as uncertain; authenticate/reread main and inspect full expected snapshot before classifying |
| Unable to reliably reread | Report unknown outcome/storage error; no RESULT and no automatic second save |

The migration safety copy is offered as an explicitly labelled read-only recovery
source. It is never chosen automatically as newest or restored over the main
file. It may predate later manual additions and imports. This phase adds no
writable repair, downgrade, merge or silent receipt deletion. If the main file
is unreadable, users can view supported safety-copy accounts with the original
password and rely on service recovery methods for missing newer accounts.
Restoring writable use needs a separate, explicitly reviewed recovery workflow.

After transfer save uncertainty, a valid matching retained receipt permits
STATUS/RESULT after unlock and authentication. Unknown IDs return UNKNOWN,
never proof of non-commit. A known ID with another digest is TX_CONFLICT.
Never combine accounts from one candidate with receipts from another. Process
restart discards session credentials and pending approval; it retains receipts
only through the encrypted vault. Old desktop sessions cannot resume after
desktop restart; a fresh session and duplicate checks are required.

## Compatibility, backup and threats

| Application / operation | Required behavior |
| --- | --- |
| Existing Watch or pinned Desktop 0.10.0 opens Watch v2 | Reject the unsupported header; do not modify, reset or reinterpret the file |
| Updated Watch opens an ordinary supported v1 file | Read and write v1; unlocking or manual addition does not migrate it |
| Updated Watch opts into transfer on v1 | Explain compatibility and recovery limits; migrate only after explicit local consent |
| Desktop sends selected accounts | Implement transfer wire v1; no Watch v2 reader or Desktop vault migration required |
| Desktop keeps its own vault | Continue using its existing format; transfer does not remove or migrate its accounts |
| Desktop directly reads a Watch v2 file in a future import feature | Requires a separately scoped v2 reader; this work does not provide it |
| User downgrades Watch after migration | Older Watch cannot open the v2 main file; no automatic downgrade is provided |

The network document version **v0.3**, JSON wire field **v:1**, binary Watch
header **0x02**, JSON vault **version:2**, and application release number are
independent. None permits guessing another format. The initial supported
Desktop source reference remains commit
`7d1089d6bee0f3f82678ca8a13988c742840fba0` (v0.10.0); claims about newer readers
require actual inspection/testing. This design does not reserve a shared
Desktop storage version or claim that Desktop implements Watch v2.


Old readers reject v2 and must leave it intact. New readers must continue
reading/writing ordinary v1 without migrating on unlock. The existing pinned
Desktop v1 corpus stays immutable and must continue passing. No whole-vault
export or transparent downgrade is added. Desktop transfer implementation needs
no Watch password or Watch storage reader.

Current backup rules already exclude the main/backup/temp paths. Keep those
exclusions, put the retained safety copy under Android no-backup storage and
verify cloud-backup/device-transfer behavior. No receipt sidecar is allowed.
Recovery copies increase encrypted data retention; v1 password-guessing costs
remain unchanged. Safety copies do not protect against a lost password, malware
or a hostile OS. PSKs and pending offers remain memory-only. Authenticated local
storage does not authenticate a network peer. Tests do not establish an audit
of the PAKE/importer/TLS composition or guaranteed memory erasure.

## Required implementation evidence

- v1 fixtures unchanged through ordinary use; v2 rejects old/unknown readers
  safely; exact preservation of nullable labels, duplicate names, parameters,
  legacy key spelling and all receipts through load/manual-save/restart.
- Wrong password, modified header/salt/nonce/ciphertext/tag, malformed versions,
  duplicate keys, invalid receipts, oversized data and unsupported fields;
  rejection preserves every original file.
- Fault injection at safety-copy creation/write/sync/verification, temp write,
  backup creation, atomic move, cleanup and reload; process death before/after
  publication; original or complete new snapshot only.
- Receipt eviction/order, all-duplicate save, replay/ID conflict, concurrent
  mutation requiring renewed approval, CANCEL before/after save, Lock, lifecycle
  invalidation and unknown outcomes; no repeated or partial additions.
- Actual Android backup/restore, atomic replacement, directory durability limits
  and physical-watch KDF/lifecycle checks. Emulator/JVM results do not replace
  physical or cross-platform evidence.

Codec and receipt-boundary tests are recorded in the [v2 regression report](../testing/vault-v2-regressions.md).
Migration, receiver, native transport and device tests are not completed by the
codec implementation. The acceptance gates remain mandatory.
