# Shared manual/batch commit (#36)

Baseline: `c431286` (#51), branch `refactor/36-serialized-additions`.
Only public synthetic accounts, keys/passwords and temporary files are used.

## Scope and threat review

One immutable proposal captures the expected baseline and additions on the UI
thread. All account data is validated before mutation. The existing process-wide
session mutex serializes actual runtime writers and reads, across Activity
owners. Authenticated current storage must still match the expected data before
one whole-candidate save. A stale proposal is rejected for review, not silently
rebased. There is no receiving endpoint, new permission, global account ID,
receipt persistence, format/KDF change or multi-process coordination added.

The manual wizard retains standalone validation/collision behavior. Generic
in-memory additions do not resolve future receiver collisions or consent;
those remain #32/#33. Existing duplicates/null names/parameters are preserved.
The writer assumption and internal APIs are documented in
[development](../development.md#serialized-additions-36).

## Checks

- Candidate tests mutate input/output collections after capture, preserving the
  private proposal, historical duplicates/null names and supported parameters.
  Invalid first/later/baseline data, empty batches and unsupported versions are
  rejected before exposing a writable candidate.
- Session tests submit two competing batch/manual requests with controlled gates
  and executor barriers. The first commits the entire batch; the queued stale
  request cannot overwrite it. No sleeps determine submission order.
- Other session cases verify one commit call, invalid/failing batches, queued
  cancellation and Lock/cancellation after commit. Already-committed entries
  remain intact and an old operation cannot reopen its session.
- Real encrypted-file tests exercise a complete two-entry save and injected
  prepublication failure, requiring exactly one save, unchanged original bytes
  on failure and unchanged existing accounts/parameters on success. The existing
  B4 fault matrix remains responsible for detailed commit/uncertain outcomes.

JVM success does not establish Android lifecycle, Keystore, physical power-loss
durability or a transport security audit. Exact execution results are recorded
after verification; the case descriptions above are not a claim of passing tests.
