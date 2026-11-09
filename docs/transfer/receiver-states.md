# Planned receiver states (#33 / E3)

Status: **documentation-only draft**, following E1 and the recommended
[E2 memory-only/reconciliation proposal](https://github.com/wrogistefan/desktop-2fa-watch/pull/57).
E2 owner choice and the [E4 transport/authentication profile](https://github.com/wrogistefan/desktop-2fa-watch/pull/58)
remain gates. No screen, listener, permission or receiving code is implemented.

Watch remains a standalone offline authenticator. Desktop configuration is
optional; no permanent connection/account/phone is required by this proposed
flow. Existing local setup/unlock/list/manual-add/recovery behavior remains the
current product. The labels below describe future interactions only.

## Entry, limits and sensitive state

Receiving starts only through an explicit local **Receive accounts** action from
a writable, manually unlocked session. Read-only recovery/storage-error states
cannot receive. A locked/default app has no pending-secret listener or notification
queue; an attempted offer gets no route or a bounded generic unavailable result.
It must not open a keyboard, authenticate, save, or restore an old offer remotely.
If needed, the user unlocks locally first; no remote vault-password request exists.

Pending package/preview/approval exists only in the foreground receiver owner's
memory. Never put keys, labels/package data, passwords, codes or private digests
in saved state, Intents/navigation arguments, notifications, clipboard, logs,
crash attachments or a plaintext file. Process recreation starts closed. The
existing non-secret B4 storage-review notice is not a persisted pending package.

Proposed independent bounds (the earliest deadline always wins):

| Phase/resource | Ceiling |
| --- | --- |
| Entire explicitly opened receiver/key lifetime | 5 minutes from local start, monotonic; no passive renewal |
| Discovery | 30 seconds, at most 8 bounded non-secret results |
| Peer verification/pairing | 120 seconds within the total lifetime |
| Connect / authenticated package read | 10 seconds / 15 seconds |
| Public certificate preflight | At most two 8 KiB messages; no account body; strict framing/types |
| Other control/ack message | At most 2 KiB; no compression; at most one pending operation |
| Failed authentication/preflight | At most 3 attempts per opened receiver, then close; no background restart |
| Validated package awaiting consent | At most 120 seconds, within total lifetime |
| Package | E1's 64 KiB, 32-account and decoded string limits |
| ACK-only result screen | Up to 30 seconds, within total/session/receipt deadlines |
| Receipts | E2's at most 8, 5 minutes after completion, cleared when session closes |

TLS handshake/provider allocation, certificate-chain and pre-authentication rate
bounds must also be fixed and demonstrated by E4 before implementation. The app
body/control limits are not a claim that a TLS provider is already bounded.
The proposed phase budgets are ceilings, not physical-watch measurements;
#30/#37 must validate the UX without reducing v1 KDF protection.

C2/C3/C4 always apply: explicit Lock, screen off/device authentication lock,
Activity stop and 60 seconds without qualifying input invalidate the receiver
and pending approval. User touch/rotary/editing/navigation may renew C4 inactivity;
network frames, handshakes, code ticks, passive focus and storage completion may
not. An IME overlay/pause alone preserves an unexpired owner, but cannot suspend
these bounds. True background/device lock wins over editing.

## State/event table

| State | Visible interaction and allowed work | Event → next state/invariant |
| --- | --- | --- |
| CLOSED / LOCKED | Ordinary local app; no receiver/pending plaintext | Explicit local unlock/start only; incoming offers cannot reopen it |
| LOCAL_UNLOCK | Existing manual password UI, including native IME; no incoming package | Success + explicit start → PEER_SELECTION; Cancel/Lock/expiry → CLOSED |
| PEER_SELECTION | Bounded discovery/picker or approved E4 local path; names untrusted | Select one → PAIR_VERIFY; denial/error/timeout/Cancel → CLOSED |
| PAIR_VERIFY | Verify both intended peers locally using approved E4 procedure; no TOTP body yet | Both authenticated/bound → RECEIVE_VALIDATE; mismatch/interruption/expiry → discard/close |
| RECEIVE_VALIDATE | One bounded package; full E1/shared validation before actionable preview | Valid + writable owner → PREVIEW; any invalid/unsupported item → fixed error/discard |
| PREVIEW | Count/list/details, all E2 collision choices; keys/codes never displayed | Resolve choices → APPROVAL_READY; Cancel/expiry/Lock → discard/close |
| APPROVAL_READY | Exact selected indices/baseline/peer/package bound; **Add N accounts** or explicit skip-all | Local final tap → COMMITTING or NO_CHANGES; any change invalidates approval/re-preview |
| COMMITTING | One B5 candidate/writer; progress and always-available Lock; no parallel manual/receiver mutation | B4 clean verified commit → RESULT; non-clean → STORAGE_REVIEW; invalidation stays closed regardless of late result |
| RESULT / ACK_ONLY | **Saved N accounts**, bounded best-effort acknowledgement/cached replay | Done/deadline → CLOSED; lost ACK while same receipt survives → return same outcome, no new write |
| NO_CHANGES | **No accounts added**, explicit skipped count; no empty write | Bound acknowledgement/Done → CLOSED; never claim a committed addition |
| STORAGE_REVIEW | Actual known saved/unverified/uncertain guidance; ordinary local storage review | No receiver restart or blind resend; manual unlock/reconciliation after inspection |
| CANCELLED / EXPIRED | Generic closed status; transient data/keys/receipts dropped | Fresh local start only; an already-started writer can still finish under B4 |

Do not acknowledge a successful import merely because a socket closed, a timeout
elapsed, cancellation was requested or a save returned without inspecting B4.
COMMITTED_WITH_RECOVERY_ARTIFACTS is saved/review-required; failed verification
does not turn a known commit into a retryable failure. UNCERTAIN needs review.
If the original owner remains valid, a bounded terminal status may be sent before
closing for review. If Lock/invalidation wins, no acknowledgement or receipt
restoration is promised; local B4 guidance remains authoritative.

All asynchronous network/validation/storage publication must check C3 ownership
and C4 deadlines on the UI boundary without treating callbacks as user input.
A late read, error, preview or ACK cannot restore plaintext or unlock a session.
Queued invalidated work does not start; already-started synchronous writes finish
their existing protocol. Do not cancel a mutex/writer and assume rollback.

## Small round-screen interaction

Show one clear heading, peer-verification status and **N selected / M to add**.
A scrollable compact list opens per-item full-label/parameter details. Null and
empty labels have explicit distinct descriptions; formatting/bidirectional
characters and clipped/confusable labels must have an unambiguous visible details
view. Secret keys and current codes are never preview identifiers. All unresolved
collision/unnamed/intentional-duplicate choices must be visited and resolved;
do not imply the UI can prove the user read or understood every value.

Use E2's Add/Skip/intentional extra-copy choices. No existing-record edit/remove,
automatic rename, forced non-null name or implicit first-row choice. A stale
baseline clears consent and requires a refreshed authenticated preview. Final
**Add N accounts** is separate from scrolling/navigation and keyboard Next/Done;
IME submission can only complete the local password field, not approve transfer.

Lock/Cancel remain reachable with large touch targets and accessible labels.
Focus/IME loss alone pauses displayed codes; progress feedback does not extend
inactivity or acquire a wake lock. A result remains briefly ACK-only until Done
or its deadline, so a dropped ACK can be retried without another write. Starting
another proposal requires a fresh local action and consent, never a push queue.

After receipt/session/process loss, show **Delivery needs checking** and require
E2 reconciliation: manually unlock, inspect all matches, keep existing/no addition
or explicitly approve a new proposal. Never display an automatic **Try again**
that can duplicate a possibly committed account. Matching data is not proof of
that operation's history, especially with historical duplicates.

## Synthetic state traces and later evidence

| Trace | Expected result |
| --- | --- |
| Offer while locked, screen off or app stopped | No pending secret/body/notification; no auto-unlock |
| Password IME opens, edits, Next/Done returns | Preserve unexpired password draft; no receipt/package/approval restored or auto-accepted |
| Valid two-item package, explicit Add both | One complete candidate/commit and actual B4 result |
| Bad second item or 33 items/oversize body | Whole rejection, no actionable prefix or write |
| Cancel preview / idle 60s / absolute deadline | Drop proposal and approval; local vault unchanged |
| Destination changes after preview | B5 stale rejection; new authenticated preview/consent |
| Lock/background during read or before queued commit | Stay closed; no late plaintext/approval publication |
| Lock/process death after publication | Preserve possible/known committed accounts; require review, never infer rollback |
| ACK lost with live receipt / after receipt loss | Cached result without write / explicit reconciliation |
| Non-clean commit/recovery-only storage | Saved/unknown review wording; no receiver writes until safe writable session returns |

Later implementation must add protocol/unit and instrumented emulator/physical
Watch traces for every boundary, including native Samsung fullscreen IME,
permission denial/revocation, true background/ambient, process loss and real
low-resource KDF/commit latency. Existing #26/#36 tests support the current local
policy only, not an unimplemented receiver. Verification here is documentation,
scenario/source/relative-link and whitespace review; no runtime tests claimed.
