# Additive collisions and retries (#32 / E2)

Status: **manual reconciliation/no durable receipts accepted by the owner on
2026-11-09**. No durable receipt/schema migration; bounded session-memory
receipts and explicit reconciliation after Lock/restart/expiry. Communication
and transfer protocol design are deferred to a separate owner-provided model;
session binding and numeric budgets below remain preparation proposals, not
approval of a transport or wire protocol. This document adds
no receiver, persistence, account IDs or edit/delete capability.

The [E1 package](package-v1.md) is independent of the local vault. Operation IDs
refer to proposals; array positions refer only to their items. Neither names nor
keys alone identify accounts. Do not use `getEntry`/first-match lookup to select
an existing record, and never replace or delete a local account during addition.

## Comparisons and explicit preview choices

After authenticating both devices and unlocking locally, compare against the
exact authenticated destination snapshot used by B5. A full semantic match uses
the tuple `(issuer, account_name, decoded key bytes, algorithm, digits, period)`.
Labels compare exact Unicode values: null, empty, case and code points remain
distinct. Compare validated key bytes privately, not their formatted spelling;
never emit a per-key hash/fingerprint, URI or generated code for this purpose.
Do not normalize labels or alter the stored originals.

Comparison is a preview aid, not a durable identity or proof that a previous
operation committed. Historical identical records may predate this transfer.
Show the count of matching rows locally and all relevant non-secret parameters;
inspect all candidates, not an arbitrary first row. Any row position is temporary
and bound to this snapshot. Secret comparisons stay local; an unauthenticated
sender must not learn whether any account exists.

| Incoming item versus existing/proposed items | Default/required Watch choice |
| --- | --- |
| New labels/data, no account-name collision | Add, still subject to final explicit consent |
| One or more full semantic matches | Skip as already present; intentional **Add another copy** requires an extra explicit choice |
| Same `(issuer, account_name)` but different key or parameters | Unresolved: show **Different account data**, require Skip or intentional Add separately; no overwrite |
| Same nonempty account name under a different issuer | Unresolved name collision: Skip or intentional Add separately; no implicit manual-wizard policy substitution |
| Same issuer, different nonempty account names | Separate records; ordinary Add preview |
| Null/empty names | Preserve their exact values and show explicit missing/empty-label text; do not invent names |
| Both labels absent/empty and no full match | Unresolved unnamed record: inspect parameters and explicitly Add or Skip |
| Repeated full-identical incoming records | Resolve the group explicitly: Skip all, Add one copy or intentionally Add all N; no silently discarded prefix |
| Overlapping incoming labels with different data | Resolve each collision explicitly, with all rows visible in details |
| Visually confusing labels | E3 shows unambiguous full details/formatting; display similarity never selects an existing account |

No incoming renaming or parameter-edit form is part of the first proposal.
Users can cancel and change their selection/source separately. This preserves
nullable labels and avoids silently modifying what Desktop sent. The existing
manual wizard retains its own unique-account-name workflow.

Every received item must first pass E1/shared validation. The user may then skip
valid items deliberately. Final consent binds the original package, ordered
accepted indices and exact immutable candidate/baseline. All accepted additions
commit together once through B5. Skip-all produces **No changes**, not an empty
write. A stale baseline invalidates consent and requires manual unlock/re-preview;
no automatic rebase, partial append or first-match replacement is allowed.

## Proposed operation and receipt scope

Within one still-active authenticated pairing/receiver session, identify a
request by peer-session identity plus E1 `operation_id`. Keep a private digest
of the exact bounded package bytes and the approved index decisions. Changed
bytes or decisions cannot silently reuse that operation. An operation ID is not
an authentication token; advertised device names do not establish peer identity.
New target/changed proposal needs a new operation and fresh Watch consent.

Recommended receipt budget: at most **8 terminal operations**, each expiring
**5 minutes after completion**, measured monotonically without renewal by replay.
Allow at most one pending proposal/write. Store only operation/session identity,
private package digest, approved indices, actual outcome and expiry. Keep no raw
labels, keys, passwords or codes. A digest is secret-derived metadata: never log,
send, persist or treat it as a public account fingerprint.

Receipts and pairing state are memory-only and are cleared on Lock, screen off,
true background, receiver cancellation/session expiry or process death. Existing
C4 inactivity also wins. Retaining receipt metadata never keeps an unlocked vault
alive or creates background work. Limits are proposed design ceilings, not a
measured throughput promise. JVM reference clearing cannot guarantee erasure.

For an authenticated replay in that same live session:

- Same ID/bytes while pending: report bounded **In progress**, never start a
  second writer or extend session/receipt lifetime.
- Same ID/bytes/approved decisions after a known clean commit: return its cached
  acknowledgement, never append again or require another candidate write.
- Same ID with changed bytes/decisions: reject **Operation mismatch**. Do not
  overwrite the receipt, commit a replacement or silently invent a new ID.
- A terminal rejected/non-clean result is not permission to retry a write. If
  storage review closes the session, drop the receipt and use reconciliation.
- A late completion after invalidation may retain only B4's existing non-secret
  storage-review notice; it cannot recreate a receiver, ledger or unlocked session.

Wire replies contain only bounded outcome/count/approved-index metadata needed
for the selected proposal, after authentication and the original consent. They
must not expose unrelated local inventory, hashes, labels, passwords or codes.

## Lost acknowledgement and lost certainty

No durable receipt is claimed by this proposal. If the link drops but the same
authenticated session/receipt survives, replay can return the known outcome.
After Lock, process/app restart, expiry, eviction or loss of either side's
pending operation state, certainty is lost: **do not auto-resend or append**.
Desktop shows **Delivery needs checking**, not a false **Failed, try again**.

The honest sender declares retry intent in the future authenticated control
envelope, separate from E1's account object. Unknown retry IDs require explicit
reconciliation on Watch. A sender presenting an old operation as new still gets
full local collision preview/consent; matching records default to Skip, and
adding another copy requires an explicit duplicate decision. New IDs cannot
bypass consent or cause silent background additions.

Reconciliation reloads storage after manual password unlock, compares **all**
matching candidates and displays possible already-present records locally.
A matching record is not proof of this operation's history. The user chooses
**Keep existing/no addition**, or knowingly starts a new proposal/approval for
the remaining or intentional extra copies. If ambiguity cannot be resolved,
cancel without a write. No globally stable account IDs are introduced.

| Actual B4 result/event | Feedback and permitted next step |
| --- | --- |
| Validation/cancellation before writer starts | No new accounts from this attempt; fresh consent needed later |
| NOT_COMMITTED, including stale baseline | Not saved; storage/recovery artifacts may still require review, no blind retry |
| COMMITTED and matching verification | Saved; acknowledge/cache only while original owner/session remains valid |
| COMMITTED_WITH_RECOVERY_ARTIFACTS | Saved, storage review required; cleanup failure is not rollback |
| Known commit but verification failed | Saved but unverified; review, do not repeat |
| UNCERTAIN or crash around publication | Result unknown; inspect storage/reconcile, never infer rollback |
| Lock/cancellation after commit | Keep UI closed; preserve known/possible saved data, no late success reopening |
| Receipt expires/evicted or process restarts | No durable exactly-once claim; explicit reconciliation before any new addition |

## Persistence alternatives and decision

**Accepted storage direction:** retain the memory-only policy above. It adds no files, preference
stores, keys, backup changes or D2FA v1 migration. Existing B4 process-local
notices remain conservative storage guidance, not durable retry receipts. The
cost is user reconciliation after restart/Lock/expiry, rather than seamless retry.

**Alternative requiring separate version/recovery design:** durable encrypted
receipts atomically bound to account publication. Writing a sidecar/preference
before the vault can falsely claim a commit; writing it afterward leaves a crash
gap. A last-operation preference, public digest or per-account secret hash does
not solve this. Changing encrypted v1 JSON would break strict unknown-field
readers and cannot be slipped in as metadata. A new version or proved journal
would need migration, backup/transfer exclusion, missing-key handling, receipt
expiry/cleanup, rollback/recovery and multi-file fault tests in separately scoped
work. Losing a receipt/key must never reset the vault.

The owner accepted explicit manual reconciliation after lost confirmation and
restart, without durable receipts or a vault-format change. This is not a
durable delivery guarantee or permission to implement networking; the separate
communication/transfer model must still resolve authenticated session binding
and final control-message semantics.

## Synthetic review traces

| Trace | Required invariant |
| --- | --- |
| New operation with two new synthetic accounts | One preview/consent/candidate/write, preserving pre-existing entries |
| ACK lost, exact replay while receipt live | Cached outcome; account count unchanged |
| Same ID, one changed period/key/label or different selection | Mismatch rejection; no replacement or new append |
| Two overlapping proposals/legacy identical copies | All matches reviewed; no first-match selection or auto-overwrite |
| One bad item among valid items | Whole-package rejection before any candidate publication |
| Crash before publication | No presumed commit; restart inspection/reconciliation |
| Crash after publication before receipt/ACK | Saved data may exist; no automatic resend |
| Receipt expiry/eviction or new process | Reconcile; no false exactly-once promise |
| Manual addition changes destination after preview | B5 stale rejection and new consent |
| Skip all or cancel before writer | No vault write, no phantom committed-add acknowledgement |

Verification: documentation/source/B4/B5 consistency and scenario-table review,
plus whitespace/link checks. No receipt implementation or runtime tests claimed.
