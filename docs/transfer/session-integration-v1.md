# Transfer storage integration with the Watch session

The session API is internal and has no receiver UI or network caller yet. Only a
manually unlocked, writable v2 session on the main screen may explicitly begin
one receive owner. Opening does not migrate a v1 vault or save any accounts.

The opaque owner token expires on Lock/disposal. An OFFER is prepared on the I/O
dispatcher under the same process-wide mutex as unlock and manual writes. The
review candidate object identifies the exact local consent. A second OFFER may
not replace a pending review. Manual additions cannot run while it is pending.

Approval uses the existing lifecycle-owned, non-cancellable mutation boundary
and that same mutex. The publication backend reauthenticates/rechecks the file.
A stale baseline requires fresh planning and a new candidate/local approval;
there is no automatic approval. Authenticated retained receipts replay without
writing again. Cancellation while preparing prevents a late review from appearing.

Lock/disposal signals the atomic approval gate before invalidating UI ownership.
Before the save boundary this cancels publication. After the boundary the write
finishes, while accounts and pending approval disappear immediately. An old
owner cannot reopen UI or publish a transfer result into a new session. The
existing non-secret late-save notice asks the user to unlock/review storage.
Unconfirmed publication and recovery warnings leave the writable session.

A future network controller must distinguish cancellation request signals from
terminal storage results and implement wire CANCEL/TOO_LATE/RESULT/STATUS. It
must check session ownership and the existing inactivity deadline on the UI
dispatcher without counting incoming frames/heartbeats as local user activity.
Only local taps may refresh inactivity. This API alone does not implement the
protocol, global receiver ownership across Activities, timeouts or Wi-Fi cleanup.

Tests use synthetic in-memory storage through the real commit backend and
controlled executor/latch scheduling. Device lifecycle, process death and paired
Desktop acceptance remain NOT RUN and are deferred to final acceptance by the
owner. JVM tests are not evidence of those platform behaviors.
