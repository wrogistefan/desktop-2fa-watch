# Selected-account transfer: implementation handoff

The owner supplied [Desktop-2FA → Watch specification v0.3](desktop-2fa-transfer-v0.3.md)
on 2026-11-12 and authorized implementation in separate branches/PRs, with review
addressed before merge. That file is an unchanged copy of the supplied document.
Its v0.3 document version, v1 wire protocol, and Watch storage version
are separate version spaces.

**Current state:** the v2 storage codec, receipt validation and receipt-preserving
manual candidates are implemented. The isolated native proof of concept is merged separately. The explicit
migration backend is implemented but has no app entry point; read-only archive
recovery and internal transfer planning/approval ownership in the password session
are implemented. The confirmation UI and network receiver remain pending. No receiver or network
permission is enabled in the app. The owner accepted
the [v2 format, migration and recovery design](vault-v2-proposal.md) on
2026-11-12 after reviewing its Desktop compatibility consequences, and required
explicit documentation. Design approval is not an implementation or release result;
each end user must still consent locally before their v1 vault is migrated.

## Owner execution-order amendment — 2026-11-13

The owner explicitly authorized continuing the full Watch implementation before
physical-watch testing, with device and paired Desktop acceptance deferred until
the Desktop side is ready. This changes the execution order in the supplied
specification, not its wire profile, cryptography or acceptance criteria. Continue
automated JVM, fault, interoperability, build and PR/CI checks throughout. Mark
unexecuted device checks NOT RUN and keep the transfer experimental until the
required final acceptance is completed. No Desktop repository/application change
is authorized by this Watch task.

## Relationship to roadmap #13

The owner's current authorization starts the previously deferred communication
work. It does not mark outstanding hardware evidence complete or permit real
secret reception before the supplied specification's acceptance gates.

| Earlier material | v0.3 direction and consequence |
| --- | --- |
| E1 selected-account package | Use the supplied strict OFFER schema, 1–50 items, canonical unpadded uppercase Base32, mandatory names/parameters and v1 budgets; do not reuse E1's nullable-label wire contract |
| E2 manual collision choices | Skip matching secret/parameters; reject parameter/name conflicts and duplicates inside the incoming batch; no intentional duplicate-copy choice |
| E2 memory-only receipts | v0.3 requires the latest 128 encrypted receipts, committed with additions; Watch v2 design accepted on 2026-11-12; codec and publication/session foundations implemented; full receiver acceptance pending |
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

The isolated native implementation uses BoringSSL commit
`594a44291086dcc7cef06cabec4c0b47afd674c0`. Host Linux/Windows/macOS JNI/CFFI
1000-exchange tests and the documented Linux sanitizer/upstream suites passed.
The separate synthetic Watch transport APK was built and guarded for three
ABIs; its CI uses SDK 37, NDK 28.2.13676358 and CMake 3.22.1. The product app
keeps minSdk 30/targetSdk 37 and remains offline at this stage.

These results supersede the initial source-only checkpoint and initial lack of
local native tooling. They do not complete T0–T12: upstream security-fix
assessment, adversarial/composition cases and real-device/paired-app acceptance
remain open. The [acceptance ledger](../testing/transfer-native-poc-2026-11-12.md)
records exact executed scopes and unexecuted gates; only its labelled host
subset T1 is passed, not the full transfer release gate.

The accepted storage design explicitly covers migration, no-backup safety copy,
older-reader rejection and recovery. A second integration concern is the current
60-second inactivity lock versus up to five minutes of transfer review: existing
locking wins unless the owner separately approves a changed session policy.
Network heartbeats must never count as user activity. The UI must explain that
locking discards pending approval and may require starting a fresh session.

## Isolated native proof of concept

The [developer-only native experiment](../../native/README.md) now contains the
shared SPAKE2/importer/TLS core, a bounded C ABI, JNI adapter and a private-pipe
Python/CFFI ↔ Kotlin/JNI harness. It is separate from the app and from the v2
storage foundations in PR #67. The isolated smoke APK/Python peer adds a
synthetic Wi-Fi transport in its own UID, without account/OFFER support. The
product app has no network receiver/permission or real-account transfer UI. See the [acceptance ledger](../testing/transfer-native-poc-2026-11-12.md)
for executed checks and T0–T12 gaps; source code or prepared CI is not proof of
passing the required real-Wear/all-platform gate.

- [OFFER validation and pure planning](offer-validation-v1.md): strict wire records, shared synthetic vectors, historical conflicts and private receipt candidates; no receiver/save integration.

- [Transfer commit boundary](commit-boundary-v1.md): local approval/cancellation gate and receipt-verified encrypted publication backend.

- [Session storage integration](session-integration-v1.md): shared writer mutex, consent ownership, cancellation and Lock; receiver UI/network integration remains pending.

- [Authenticated wire codec](wire-codec-v1.md): strict directional messages, exact-byte OFFER digest and bounded framing; controller/TLS/network integration remains pending.

- [Explicit activation session contract](activation-v1.md): identity-bound local migration consent, retained v1 recovery copy and older-reader consequences; no app entry point yet.

- [Protocol state and STATUS](protocol-state-v1.md): one-batch ordering, exact local approval, fresh authenticated receipt reads and monotonic budgets; session/transport effect adapter remains pending.

- [Serialized session owner adapter](session-owner-v1.md): protocol-to-storage effects, lifecycle ownership, bounded output and process-wide receive lease; native/socket/UI caller remains pending.

- [Optional native app build](native-app-build.md): explicitly enabled pinned JNI packaging, default-off offline app and artifact guards; no network/runtime receiver is enabled.
