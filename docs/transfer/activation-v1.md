# Explicit Watch transfer storage activation

The internal session API adds no UI entry point yet. It prepares a private v1
baseline and an identity-bound review only after an explicit local action in a
manually unlocked, writable main session. Unlock and remote messages never
request/confirm migration. Refusal and Lock discard that review; it is never
saved in preferences or restored by Android.

The future confirmation screen must state all of the following before exposing
the confirmation action:

- Transfer requires the Watch v2 receipt format. Ordinary v1 use can continue
  without enabling it. No Desktop vault migration is involved.
- The main Watch vault is rewritten as authenticated v2 only after confirmation.
  Older Watch/desktop readers cannot open that main file.
- The exact encrypted v1 safety copy is retained at app-private no-backup path
  `transfer-migration/vault-v1.d2fa`. It requires the original password and is
  read-only recovery, not a current synchronized backup.
- The copy lacks accounts added after activation; returning to it loses those
  later changes. No automatic rollback, archive overwrite or silent retry occurs.
- Android backup/device migration does not provide recovery of this private
  no-backup copy. App-data clearing/uninstall can remove it. This flow does not
  export the copy or transmit the Watch password/vault to Desktop.

Confirmation captures the exact reviewed baseline, consumes that review and
uses the existing process-wide session mutex/non-cancellable mutation. The
migration backend independently authenticates the file and checks that baseline,
verifies the unchanged encrypted archive, publishes through the existing atomic
writer and reconciles the actual file. Changed data requires a new review.
The session returns to writable v2 only for a clean confirmed result. Any failure
or recovery warning closes writable access and requests storage review.

Lock before queued work enters storage prevents migration. Once synchronous
migration has started, Lock hides data immediately but permits the archive/write
protocol to finish. A late result cannot reopen the session; it leaves the
existing non-secret storage notice. Migration success does not start networking,
generate a pairing code, or count as transfer approval. A separate local receive
action and whole-batch review remain necessary.

Desktop must implement the wire profile and durable-result/retry handling; it
does not read this safety copy or change its own vault format. This task makes
no changes in the Desktop repository. Device/process-death/paired-app tests
remain required at final acceptance, as authorized by the owner.
