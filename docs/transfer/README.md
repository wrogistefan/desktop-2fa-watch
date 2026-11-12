# Selected-account transfer: implementation handoff

The owner supplied [Desktop-2FA → Watch specification v0.3](desktop-2fa-transfer-v0.3.md)
on 2026-11-12 and authorized implementation in separate branches/PRs, with review
addressed before merge. That file is an unchanged copy of the supplied document.
Its v0.3 document version, v1 wire protocol, and future Watch storage version
are separate version spaces.

**Current state:** the v2 storage codec, receipt validation and receipt-preserving
manual candidates are implemented. No receiver, network permission, native wrapper
or storage migration is added by this storage-foundation change. The owner accepted
the [v2 format, migration and recovery design](vault-v2-proposal.md) on
2026-11-12 after reviewing its Desktop compatibility consequences, and required
explicit documentation. Design approval is not an implementation or release result;
each end user must still consent locally before their v1 vault is migrated.

## Relationship to roadmap #13

The owner's current authorization starts the previously deferred communication
work. It does not mark outstanding hardware evidence complete or permit real
secret reception before the supplied specification's acceptance gates.

| Earlier material | v0.3 direction and consequence |
| --- | --- |
| E1 selected-account package | Use the supplied strict OFFER schema, 1–50 items, canonical unpadded uppercase Base32, mandatory names/parameters and v1 budgets; do not reuse E1's nullable-label wire contract |
| E2 manual collision choices | Skip matching secret/parameters; reject parameter/name conflicts and duplicates inside the incoming batch; no intentional duplicate-copy choice |
| E2 memory-only receipts | v0.3 requires the latest 128 encrypted receipts, committed with additions; Watch v2 design accepted on 2026-11-12; implementation/evidence pending |
| Deferred E3/E4 drafts (#60/#58) | Historical references; do not combine their provisional framing, budgets or transport ideas with v0.3 |
| Current vault v1 / pinned Desktop corpus | Preserve existing valid data and tests; wire interoperability does not require identical vault formats |
| #10 whole-vault import/export | Separate scope, unchanged |

## Implementation sequence

1. Implement the accepted Watch storage and recovery design with regression tests. Keep ordinary v1 operation
   unchanged while native proof-of-concept work proceeds independently.
2. Build an isolated shared BoringSSL core at the exact pinned revision with
   JNI/CFFI adapters. Run the SPAKE2/importer/TLS gate with synthetic nonsecret
   marker data before building the full transfer UI. Record toolchains/ABIs.
3. Add shared strict JSON/framing/Base32/account/TOTP fixtures for Desktop and
   Watch. No secret transmission before full TLS authentication and profile checks.
4. Implement approved transactional receipts, duplicate planning and lifecycle
   ownership; test faults, restart, locking, cancellation and renewed approval.
5. Add Wi-Fi-bound networking/discovery/manual addressing and Watch consent;
   verify supported API/permission behavior and required real-device evidence.
6. Publish exact T0–T12 evidence and address PR reviews before merge. Release
   remains blocked on required platform gates and security composition review.

Desktop implementation must follow the supplied specification rather than Watch
internal model serialization. It needs the same pinned native core, exact byte
contexts and roles, configurator-only memory behavior, OFFER byte retention,
STATUS/retry semantics and unknown-outcome UI. Desktop accounts remain intact;
no Watch password or whole-vault file is transmitted.

## Current evidence and open decisions

On 2026-11-12, a local upstream source clone contained commit
`594a44291086dcc7cef06cabec4c0b47afd674c0`. Source inspection confirmed declarations
for `SPAKE2_CTX_new`, `SPAKE2_generate_msg`, `SPAKE2_process_msg`,
`SPAKE2_MAX_KEY_SIZE=64`, `SSL_CREDENTIAL_new_pre_shared_key`,
`SSL_set1_group_ids` and `SSL_get_negotiated_group`.
This is partial T0 evidence only: no upstream fixes review, native build,
wrapper, sanitizer, authentication or platform compatibility result is claimed.

The local Android SDK has no installed NDK or CMake. Current app minSdk is 30,
compileSdk/targetSdk 37. Native build versions, supported ABIs, desktop packages
and API-specific LAN/discovery permissions still need verification. No SDK/OS
support change has been made. T0 is incomplete; T1–T12 are **NOT RUN**.

The accepted storage design explicitly covers migration, no-backup safety copy,
older-reader rejection and recovery. A second integration concern is the current
60-second inactivity lock versus up to five minutes of transfer review: existing
locking wins unless the owner separately approves a changed session policy.
Network heartbeats must never count as user activity. The UI must explain that
locking discards pending approval and may require starting a fresh session.
