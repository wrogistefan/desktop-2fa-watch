# Serialized Watch session/transport adapter

`VaultTransferOwner` connects `TransferProtocol` effects to the existing
`VaultSession` planning, receipt lookup, local approval and atomic commit API.
It has no socket, discovery, native key or receiver screen. Only a manually
unlocked v2 main session can acquire it. Its future Activity caller must use the
UI dispatcher for every action and the original START+15-minute deadline.

The adapter observes a bounded StateFlow revision signal emitted after session
I/O completion or invalidation, rather than polling Compose state. Storage stays
on the existing I/O dispatcher/process mutex. Incoming PING/PONG and local cancel
remain responsive while the writer is blocked. Candidate identity is preserved
from review to approval; stale publication triggers fresh planning and consent.
STATUS always executes the current authenticated file lookup, never a cached
confirmation. No network event is interpreted as user approval or migration.

Each root receive token records its invalidation reason. Lock/disposal closes
the old owner; storage-review closure emits a fixed storage error. An invalidated
owner cannot use a newly unlocked session. Readiness/phase is observable Compose
state, while the candidate remains in the session and is never saved by Android.
The activity's new internal `networkAction` checks owner/deadline without
calling `input()`: packets and heartbeats cannot renew local inactivity. Local
approve/cancel taps must still pass through `userAction`.

On disconnection the old draft/gate is cancelled without sending a false wire
CANCELLED; an already-started atomic writer finishes. A replacement authenticated
TLS channel sends HELLO/READY immediately, then queues its required STATUS until
the old writer settles. Only that read waits; heartbeat remains responsive.
The same receive owner is rearmed before executing the fresh read. Any
old result belongs to the old channel; the replacement reads the actual receipt.
Rearming is for a retained authenticated credential only, not an unlock, new
PAKE or restored preference. Native authentication, at most two reconnect
attempts/two-second spacing and network timeouts remain transport obligations.

The outgoing command channel has capacity 16. An unconsumed peer terminates with
BUSY instead of accumulating work. Closing/disconnecting drops obsolete queued
messages before publishing terminal metadata/closure. The consumer must close
native contexts, socket and network callbacks on EndSession, channel end, scope
cancellation or I/O failure, in its own finally block. Session/adapter scope
cancellation closes the output; it does not interrupt a started atomic write.

A process-wide UI-owned lease prevents two Activity owners from receiving at
once. It is deliberately retained after protocol closure: only `transportClosed`
acknowledges that native/socket/network resources are actually gone and releases
the lease. The future transport must acknowledge from its finally block on the
UI dispatcher, even when cancelled. Merely changing a UI phase must not permit
a second native channel. No app entry point calls this adapter yet.

Synthetic executor/latch tests cover full preparation/approval/receipt replay,
responsive heartbeat/TOO_LATE during a blocked writer, Lock before save,
reconnect after dropped review and during commit, new consent after stale data,
local idle expiry, bounded output, scope cancellation and resource-lease release.
These do not establish real Android lifecycle, power-loss durability, Wi-Fi
routing or paired Desktop behavior. Those remain final acceptance tests.
No Desktop repository/application, KDF or vault format is changed here.
