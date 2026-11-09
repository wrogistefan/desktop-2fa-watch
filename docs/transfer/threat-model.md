# Transfer threats and local transport options (#34 / E4)

Status: **documentation-only recommendation; owner transport decision pending**.
No network permission, dependency, service, socket, pairing or receiver is added.
The complete authentication profile and physical/protocol evidence remain gates
before a separately authorized implementation. This is not a security audit.

## Assets, parties and boundaries

Protect selected TOTP keys/parameters/labels, both vault passwords, local vault
data, pairing private keys and package/approval state. Watch and Desktop are
independent offline applications. Only selected additions cross an explicitly
opened, mutually authenticated encrypted session; neither whole vault nor its
password is sent. Storage AES-GCM/Argon2id and old Keystore preferences do not
authenticate a remote endpoint.

Assume an untrusted network, discovery records and advertised names, including
an active nearby MITM, wrong devices, replay, reordered/duplicated delivery and
connection interruption. Do not trust proximity, a Bluetooth bond, a LAN address,
an account name or a successful unauthenticated socket as application identity.
Both humans must be able to verify the intended peer and approve locally.

Trusted boundaries are the installed applications/OS/providers, honest local
consent UI and the chosen authenticated key-binding procedure. A rooted or
compromised device/app, malicious keyboard/accessibility tooling, observation,
coerced approval and all JVM memory copies are not solved. Packet confidentiality
does not make displayed account names/keys safe from an observer. No hardware-
backed key guarantee, audit or certification is implied.

## Feasible transports and actual constraints

| Option | Phone/cloud/account | Platform and usability cost | Assessment |
| --- | --- | --- | --- |
| Direct local Wi-Fi/LAN + TLS 1.3 | No product phone/cloud/account required | Same reachable local network, watch Wi-Fi availability, firewall/picker permissions and explicit network binding; TLS exists in Python/Android stacks | Recommended first candidate, subject to owner approval and actual device tests |
| Direct BLE/GATT | Can avoid phone/cloud/router | Watch advertising/GATT capability plus separate Desktop OS backends, fragmentation/reassembly, reconnect and authenticated protocol/library work; a BLE link alone is insufficient authentication | Feasible research alternative, not a ready interchangeable drop-in |
| Wear Data Layer | Paired Android device/Google Play services; may route through Google infrastructure | Not a direct Desktop API; a phone companion/bridge would be additional product scope | Does not meet the proposed direct, phone/cloud-independent first profile |
| Developer ADB/file transfer | Developer tools/debug trust | Broad debugging capability; no current safe product receiver/consent flow | Test/development mechanism only, not the first user transport |
| Whole encrypted vault file | Depends on a separate delivery workflow | Different replacement/export/recovery model, sharing more than selected accounts | Remains separate #10, not this additive path |

Primary platform evidence reviewed 2026-11-09:

- [Wear standalone networking](https://developer.android.com/training/wearables/data/network-communication)
  supports direct network access; default traffic may use a phone proxy. A future
  LAN-only profile must explicitly obtain/bind its own Wi-Fi connection and fail
  closed rather than silently use phone/LTE/cloud routing.
- [Wear Data Layer overview](https://developer.android.com/training/wearables/data/overview)
  describes paired Android/Wear communication and possible intermediary routing.
  The existing dependency in this project is not evidence of a direct Desktop path.
- [Android local-network protection](https://developer.android.com/privacy-and-security/local-network-permission)
  affects target SDK 37: a system-mediated NSD picker may scope connections;
  otherwise applicable local access requires permission. Prefer the least-scope
  picker route if supported on actual Wear hardware; do not silently add broad
  permission/fallback. Denial/revocation must leave standalone use intact.
- [Python ssl](https://docs.python.org/3.13/library/ssl.html) and
  [Android SSLContext](https://developer.android.com/reference/javax/net/ssl/SSLContext)
  provide established TLS APIs; actual packaged OpenSSL/provider capabilities,
  certificate validation and supported device builds still need verification.
- [Bleak's backends](https://bleak.readthedocs.io/en/latest/backends/index.html)
  are OS-specific, and [Android advertising capability](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#isMultipleAdvertisementSupported())
  must be checked. This establishes candidate APIs, not a validated end-to-end
  authenticated Desktop↔Watch BLE solution.

These are engineering inferences from the documented APIs, not successful
transport, discovery, battery, permission or packaged-Desktop tests. In particular,
the system picker UX on the supported Wear versions is an explicit feasibility gate.

## Recommended security requirements, not an implemented handshake

Use established TLS 1.3 and its certificate/key validation for the LAN candidate,
with application-level operation/consent checks. Do not design encryption from
AES-GCM at rest, send vault passwords, accept arbitrary certificates, or weaken
TLS to make discovery convenient. No secret data before full authenticated
handshake and verified binding to the physically intended peer on both ends.

The [TLS 1.3 standard](https://www.rfc-editor.org/rfc/rfc8446.html) supplies channel
cryptography, not human peer selection or application exactly-once delivery.
Disable early/0-RTT writes and unreviewed session resumption in the first profile;
transport replay protection cannot replace E2's operation/commit rules.

Candidate pairing profile for later review: per-session credentials, explicit
physical verification of both peers' **full** certificate/public-key fingerprints,
then narrow certificate trust for the actual mutually authenticated channel.
Untrusted discovery/certificate metadata may be exchanged only within separately
bounded non-secret preflight messages. Never treat that exchange or a device name
as authentication. If using QR, it contains pairing material only, never TOTP data;
Desktop camera availability and Watch's lack of an assumed camera must be considered.
No home-made shortened comparison code, trust-all fallback or automatic TOFU.

The concrete certificate provisioning, fingerprint-binding procedure, connection
roles, safe discovery/picker path and matching library/provider configuration are
**security-critical unresolved gates**, not implementation instructions. The
full-fingerprint profile is robust in principle but may be awkward on a round
watch; alternatives need an established reviewed protocol/library, not an ad hoc
6-digit hash. Validate usability and both-direction MITM resistance before approval.

Pairing/session keys must expire with the foreground receiver/absolute monotonic
lifetime and be discarded on Lock, screen off/background, cancellation or process
loss. Do not reuse the old Remember key or add persistent trusted peers by default.
If future non-exportable ephemeral Keystore aliases are chosen, namespace ownership,
startup cleanup, invalidation/loss and backup behavior need explicit tests; missing
keys never authorize resetting a vault. JVM strings/keys cannot all be proved erased.

## Threat/control/test matrix

| Threat/event | Required control and later evidence |
| --- | --- |
| Wrong peer, substituted discovery name/address | Independently verified key binding on both devices; fail authentication without opening a preview or sending secrets |
| Active MITM during discovery/certificate exchange | OOB full-key verification + narrow authenticated TLS; replace either side's key in protocol tests and require rejection |
| Passive LAN/BLE interception | Established authenticated encryption; capture only public synthetic test sessions in later tests |
| Replay, changed same-ID payload or ACK loss | E1 identity/bytes + E2 bounded receipts/reconciliation; no automatic append or rollback inference |
| Locked receiver or revoked network permission | No pending secret delivery; local unlock/explicit receiver start, bounded unavailable result, standalone use retained |
| Malformed/huge/decompressed input or connection flood | No compression; E1 64 KiB/32-item/field limits and separately bounded handshake/control messages, one pending operation, timeout/rate limits |
| Approval substitution or changed destination | Bind consent to peer/session/package/selection/baseline, B5 stale check and new preview after any change |
| Late completion after Lock/background/process loss | C3/C4 invalidation wins; B4 actual outcome remains reviewable, no reopened receiver/session |
| Commit error or crash around publication | B4 result and E2 reconciliation; no two-file receipt illusion, no blind resend |
| Pairing expires/interrupted/clock jumps | Monotonic local expiry, no acceptance based on sender wall time; discard pending data/keys |
| Logs, notifications, screenshots/ambient | No keys/passwords/URIs/codes/package/digests in diagnostics; receiver-specific visual protection must be decided before real-secret preview |
| Compromised OS/peer or observer | State limits honestly; do not claim the transport protects all screen/memory or coerced actions |

Control-plane bounds/rate limits and absolute lifetimes must be concretely fixed
and tested with E3 before implementation; they cannot remain unbounded simply
because E1 limits the eventual account body. No background listener/service,
automatic discovery loop, analytics or secret upload is authorized by this design.

## Decisions and readiness

Owner decision requested: adopt **direct local Wi-Fi/LAN + TLS** as the first
transport direction, accepting a shared-network/firewall/picker cost, or keep
transport open for a BLE feasibility phase first. No phone/cloud bridge is
silently added under either choice.

Before first transfer, all of the following remain required: owner-approved
E1–E4 choices; a concrete reviewed two-device authentication/pairing/library
profile; physical KDF/Keystore/IME evidence (#30/#37); permission/network/peer
binding tests; protocol negative/replay/timeout/commit tests and a separately
authorized implementation PR. Where the receiver threat model needs screenshot/
ambient protection, promote #27 as a receiver gate without pretending current
session locking supplies it.

No D2FA/KDF/backup format or shared AGENTS standard changes here. Future durable
peer/receipt storage would need separate migration/backup/recovery analysis and
explicit cross-repository instruction updates if the common standard changed.
This document is a public design/known-boundary review; new vulnerability details
still follow the private [security policy](../../SECURITY.md).

Verification: source/primary-documentation review, synthetic threat traces and
control matrix plus whitespace/relative-link checks. No network/protocol/device
experiment, capture or security audit is claimed.
