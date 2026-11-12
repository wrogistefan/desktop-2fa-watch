# v0.3 OFFER validation and pure transaction preparation

Implementation: `transfer/TransferOffer.kt` and `transfer/TransferPlan.kt`.
This is an offline foundation, not a network receiver or permission to enable
real-secret reception. Callers must authenticate TLS/profile first, enforce
framing/session bounds and unlock an explicitly migrated v2 vault.

## Desktop handoff: exact bytes and record schema

Serialize OFFER once; retain its exact UTF-8 bytes for retries. The digest is
SHA-256 of those bytes, without the four-byte frame prefix. Whitespace changes
the digest; no cross-language JSON canonicalization is required. Watch takes
one defensive byte snapshot before parsing and hashing. It never repairs keys,
normalizes names or substitutes TOTP parameters.

Only `v`, `type`, `transfer_id`, `accounts` are allowed. Version is integer 1,
type is the string OFFER, transfer_id is lowercase hex32 and accounts has 1–50
records. Every record has exactly `item_id`, `kind`, `issuer`, `account`, `secret`,
`algorithm`, `digits`, `period`, with the types/limits in the supplied specification.
item_id is unique lowercase hex32. Canonical unpadded uppercase Base32 must
decode to 1–128 bytes and round-trip exactly, including zero trailing pad bits.
Algorithms SHA1/SHA256/SHA512, digits 6/8 and integer period 1–86400 are preserved.

Names retain valid Unicode and leading/trailing spaces, provided account is not
whitespace-only. The 256-byte limit uses strict UTF-8, not character count;
unpaired JSON surrogate escapes are rejected. Current literal interpretation
of the supplied control/override rule rejects Unicode Cc (U+0000–001F and
U+007F–009F) plus bidi overrides U+202D/U+202E. A question to the owner about
extending this to all bidi directional marks/embedding/isolate controls remains
pending; no stricter contract is silently assumed. Any accepted clarification
must update the shared vectors and Desktop requirements explicitly.

Malformed UTF-8, BOM, duplicate keys (including escaped duplicates), noninteger
numbers, nesting above eight, unexpected fields or wrong JSON types are rejected.
Parser exceptions are replaced with fixed diagnostic codes without a cause.
No account value or decrypted JSON belongs in diagnostics.

Shared synthetic fixtures are in
[`app/src/test/resources/transfer-v1/offer-vectors.json`](../../app/src/test/resources/transfer-v1/offer-vectors.json).
`frame_utf8` is the exact decoded string to encode as UTF-8; `sha256` checks those
bytes. A vector either lists exact expected accounts or an expected error code.
The deliberately weak one-byte fixture secrets are test data only. Kotlin runs
these vectors; a future Desktop implementation must run them too. This is not
a claim that a Desktop transfer implementation has been tested.

## Planning duplicates and receipts

Preparation snapshots the validated vault. Compare decoded secret bytes with
all historical entries, accepting historical case/padding/formatting only for
local decoding. Do not rewrite existing entries or tighten their loaded-data
schema. Incoming records remain strict.

Repeated incoming secret, exact issuer/account pair or item_id rejects the whole
batch. For historical entries, same secret and identical parameters skips;
same secret/different parameters or same exact name/different secret rejects.
Inspect every match, regardless of entry order: a later historical conflict
must override an earlier duplicate. If multiple conflict categories apply,
PARAMETER_CONFLICT is reported first, then NAME_CONFLICT; either rejects all
items. Nullable historical names do not equal an incoming empty string.

An existing receipt with identical transfer_id/digest returns AlreadySaved;
a different digest returns TX_CONFLICT. No receipt returns unknown, never proof
that an old transfer was not saved. Replay does not refresh FIFO order.

A Review candidate contains private baseline/addition/receipt snapshots. Even
an all-duplicate batch requires approval and creates a receipt with no added
IDs. The candidate constructs one v2 vault containing accounts and the receipt,
retaining the latest 128 receipts in save order. Preparation never writes,
migrates or changes the source. Returned vault objects cannot mutate a candidate.

## Required integration boundary

Under the existing process/session write mutex, recheck candidate.matches
against the current vault immediately before accepting approval and starting
save as one serialized transition. If it changed, prepare a new REVIEW and get
fresh approval; never approve different added/skipped lists. Use the existing
atomic replacement path, not a new sidecar or second writer. After an uncertain
save, authenticate/reread and look for the exact receipt before reporting RESULT.
Nothing in this module sends RESULT, performs save, owns a session, handles
lifecycle/cancellation or decides commit durability. Those remain separate
integration and fault/device tests before receiver exposure.
