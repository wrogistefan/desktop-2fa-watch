# Authenticated Watch protocol state and STATUS

`TransferProtocol` is a serialized event/effect machine for one authenticated
credential/session and one batch. It does not open sockets or itself prove TLS,
read storage, approve accounts or save data. A session/transport owner must execute
its commands, return results with the exact opaque operation token, and discard
late callbacks. The product app still has no receiver entry point or permission.

After full native TLS profile verification the owner calls `authenticated`.
Watch sends HELLO and requires matching READY before STATUS/OFFER/heartbeats.
The first STATUS/OFFER binds transfer_id plus exact OFFER digest for the entire
credential lifetime. A changed binding is TX_CONFLICT. A second pending OFFER
is BUSY; no remote message can create local approval. REVIEW's candidate object
is the consent identity; a different or obsolete object cannot approve it.

Each STATUS emits a fresh authenticated lookup, including after a known RESULT.
There is no memory-cache substitute for the current file. `VaultSession` adds
that read under its existing process mutex and I/O dispatcher, with presence
checks around `Vault.load`. It never saves, migrates, refreshes receipt FIFO or
replaces a pending review. A missing retained receipt is UNKNOWN, not proof that
no historical import happened. Bad passwords, corrupt data, recovery artifacts
or unreadable storage are errors, never UNKNOWN. Query completion after Lock or
terminal cancellation cannot repopulate session state.

The protocol waits for preparation to produce REVIEW or a verified old receipt.
Local approval emits a save command for that exact candidate. Stale publication
emits another prepare command for the identical OFFER; only its new REVIEW can
be approved. The original five-minute review budget is not renewed by replanning
or reconnection within the same credential. Each confirmed result serializes
only receipt metadata. Unconfirmed storage emits STORAGE_ERROR and ends the
attempt, without automatic saving/retry. A confirmed save requiring storage
review may send its actual RESULT before closing the credential.

CANCEL during authenticated lookup/preparation waits for that read: an existing
receipt takes precedence (TOO_LATE followed by RESULT); a new unsaved plan becomes
CANCELLED. During saving it requests cancellation from the atomic save gate;
the signal alone never announces terminal cancellation. TOO_LATE precedes RESULT
if the gate has crossed the boundary and save succeeds. CANCELLED ends the
credential. Lock emits VAULT_LOCKED/closure, drops UI ownership and does not undo
an already-started atomic writer. A lost response means unknown delivery.

Disconnection emits a command to drop/cancel the old pending owner, closes the
channel and invalidates its operation/candidate references. It retains only
batch identifiers/digest and timing metadata, not OFFER/account records.
An authenticated replacement channel sends HELLO/READY and requires STATUS
before identical OFFER retry; old channel completions cannot publish results.
The future native/socket owner must enforce at most two reconnect attempts,
two seconds apart, only for retained authenticated credentials, and never two
concurrent channels. The machine does not authenticate RESUME or run PAKE.

The supplied absolute sessionDeadline must be the original Watch START time
plus 15 minutes, never a fresh deadline on TLS/reconnect. All deadlines use
injected monotonic time. Complete-frame idle budgets are
15 seconds outside REVIEW and 30 seconds within it; REVIEW requests PING every
10 seconds. The review cutoff is five minutes bounded by the original session
expiry. Heartbeats do not renew either cutoff or the app's 60-second inactivity
lock. `nextDeadline()` supplies an absolute wakeup, including credential expiry
while disconnected, so the owner needs no active polling. Read/write watchdogs
and complete-frame tracking remain transport responsibilities. Pending storage
is not an exemption from the specified network deadline: the owner must continue
receiving/responding to desktop heartbeats on the UI/reactor while KDF/I/O runs
elsewhere. A silent peer closes the channel, not a started writer. A resulting
lost RESULT is resolved with STATUS; it is never reported as rolled back.

## Threats and remaining integration boundaries

| Risk | Implemented boundary / remaining obligation |
| --- | --- |
| Cross-session/old callbacks | Opaque operation identity and exact batch binding; owner must serialize callbacks and invalidate local session tokens |
| Remote approval or replacement | No COMMIT; local candidate identity; one pending batch |
| False success after storage exception | Only confirmed receipt/result callbacks; actual authenticated STATUS; owner must use the verified commit backend |
| Cancellation hiding a prior commit | Wait for in-flight receipt reads and actual save result |
| Keepalive extending access | Fixed session/review cutoff; owner must check existing app inactivity without counting packets as user input |
| Unbounded I/O or reconnect | Existing bounded frame codec; socket write watchdog, reconnect count/spacing and native credential destruction still required |
| Process death / power loss | No persisted pending state; atomic encrypted receipts and later STATUS; device durability/paired-app acceptance remain NOT RUN |

No KDF, ciphertext format, Desktop vault or Desktop repository is changed.
Final device tests remain deferred under the owner's execution-order amendment.
