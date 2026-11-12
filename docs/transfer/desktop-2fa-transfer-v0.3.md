# Desktop-2FA → Watch: Transfer Specification v0.3

Date: 2026-10-31. Status: selected profile for a technical proof of concept and security review. No application implementation or tests have been completed.

## 1. Scope and decision status

Agreed requirements: local Wi-Fi; no phone in the transfer path; the watch initiates the connection to the computer; selected accounts are added to the existing watch vault; approval takes place on the watch; no synchronization or vault replacement. Bluetooth is outside the MVP.

Also agreed: use PAKE; defer QR to a future version. Manually entering a 26-character session key has been rejected. Selecting PAKE alone does not automatically approve a particular algorithm, library, code length, or persistent pairing.

Technical decision in v0.3: use the BoringSSL variant of SPAKE2 through the original `SPAKE2_*` API, followed by the RFC 9258 external PSK importer and TLS 1.3. Both endpoints use the same BoringSSL version. Python and Kotlin remain the application languages. The eight-digit code and limits below define the selected profile for the proposed MVP. This selection establishes a baseline for the proof of concept; it does not claim RFC 9382 conformance or a completed audit of the combined PAKE/TLS protocol.

## 2. User code and security model

1. The computer starts a time-limited session and advertises the service on the LAN.
2. The watch discovers the computer, the user selects it, and the watch opens a TCP connection.
3. After exchanging public identifiers, the watch generates and displays a code and sends its first PAKE message. The computer displays the pending attempt and a code entry field.
4. The user enters the code from the watch on the computer. The primary flow requires no typing on the small watch screen.
5. The devices run SPAKE2 and TLS using key material from this specific exchange. Authentication is complete only after a successful TLS handshake, not after `SPAKE2_process_msg`.
6. Only then does the computer send account names and secrets. Saving them requires separate approval on the watch.

The code consists of exactly eight uniformly random ASCII digits, displayed as `1234 5678`, generated using the operating system CSPRNG. Leading zeros are significant. On the computer, remove only the grouping space; normalized input must match `[0-9]{8}`. Do not convert it to a number. The code is not a vault password and must not appear in mDNS, plaintext messages, logs, or persistent state. Do not use ordinary generators such as `rand()`, `kotlin.random.Random`, or `random.Random`.

The code expires three minutes after display, measured using the watch's monotonic clock; authentication must also finish within that limit. Each code permits one SPAKE2 execution. The code is consumed when the watch sends PAKE_A; it may then be retained only to finish that one attempt. Do not start a second or concurrent attempt with the same code. A cryptographic error, incorrect code, or connection loss before authentication destroys the code and context. Do not retry PAKE automatically. The user starts another attempt with a new code on the watch, with at most three attempts per opening of the receive screen. TCP connection establishment or service discovery alone does not consume an attempt. The watch enforces the limit even against a malicious computer. Local cancellation remains available before saving begins.

PAKE restricts password guesses to active participation in an exchange; it does not give an eight-digit code 128 bits of entropy. An attacker can disrupt the session by interfering with the connection. Anyone who knows the code can act as a participant. The model assumes that the user controls both screens and neither device contains malware. Names, addresses, and mDNS are not authentication. Roles and identifiers are temporary and do not establish hardware identity. There is no persistent pairing, phone dependency, or cloud dependency.

## 3. Selected variant and libraries

### 3.1. Decision and rejected dependencies

Application profile identifier: `D2FA-SPAKE2-BSSL1`. Algorithm: the BoringSSL SPAKE2 variant using Edwards25519 and SHA-512, exposed through `SPAKE2_CTX_new`, `SPAKE2_generate_msg`, and `SPAKE2_process_msg`. The BoringSSL API references an earlier draft; do not describe this profile as an implementation of RFC 9382. Do not modify the library's parameters, points, scalar handling, or default password-scalar correction.

Both endpoints use original BoringSSL, with the same pinned baseline revision for testing: `594a44291086dcc7cef06cabec4c0b47afd674c0` (upstream commit dated 2026-10-18). This is a proof-of-concept baseline, not a claim that it is the latest vulnerability-free version. Before building, confirm that this revision contains the PAKE and importer APIs described below. Before release, review newer security fixes. Do not replace the pinned revision with a moving `main` branch. If an update is needed, update both endpoints together and repeat the interoperability gate.

Desktop: Python calls a small wrapper library through CFFI. Watch: Kotlin calls the same core through a thin JNI layer. The module covers SPAKE2 and TLS; PAKE secrets remain in the native context. This does not rewrite either application or the algorithm in C/C++. Do not use Android's system BoringSSL, private APIs, ADB, or developer mode.

The wrapper manages state and memory and calls public APIs. It must not implement curve operations or a custom KDF, HMAC, or AEAD. Ship binaries with the applications; users must not compile dependencies. Link BoringSSL privately and statically into the module, using hidden or prefixed symbols. Export only the wrapper's stable API and avoid collisions with OpenSSL used by other Python dependencies. Windows, macOS, Linux, and watch ABI compatibility are test gates, not assumptions derived from CMake support.

The tradeoff is native builds and responsibility for tracking updates. Upstream BoringSSL does not guarantee stable API/ABI and discourages general-purpose third-party dependencies. This design accepts that maintenance cost instead of maintaining changes to PAKE mathematics in small ports. A future algorithm change requires a new profile identifier; do not negotiate a weaker fallback.

Rejected alternatives:

- python-spake2: explicitly not constant-time and uses an independent parameter variant.
- spake2-cffi/spake2-c: in the inspected upstream `spake2-c/spake2.c`, `SPAKE2_generate_msg` prepares the private scalar using bytes from `rand()`. This is a concrete reason to reject the dependency without assuming that its wrapper fixes the RNG. This finding is not an audit of every published spake2-cffi package or a claim about its use in other products.
- spake2-java as a second port: no demonstrated audit and an additional interoperability burden; it is unnecessary with a shared core.
- BoringSSL's integrated `SSL_PAKE_SPAKE2PLUSV1` mode: its documentation marks the PAKE/TLS integration as experimental. Do not confuse it with the selected standalone SPAKE2 API.
- EC-JPAKE in Mbed TLS: its documentation also marks that TLS integration as experimental; it is excluded from the MVP.

### 3.2. Roles, inputs, and encoding

Watch = SPAKE2 Alice and TLS client. Desktop = SPAKE2 Bob and TLS server.

`sid`: 16 random bytes generated by the computer, serving as a public session identifier and encoded on the wire as 32 lowercase hexadecimal characters. `wid`: 16 independently random bytes generated by the watch for each attempt, also encoded as 32 lowercase hexadecimal characters. Neither is a persistent device identifier. `raw(sid)` and `raw(wid)` denote the decoded bytes.

Pass the following exact byte strings to SPAKE2, without a trailing NUL:

- A_name = ASCII(`desktop2fa-transfer/1|D2FA-SPAKE2-BSSL1|watch|`) || raw(sid) || raw(wid)
- B_name = ASCII(`desktop2fa-transfer/1|D2FA-SPAKE2-BSSL1|desktop|`) || raw(sid) || raw(wid)

Alice uses A_name as my_name and B_name as their_name; Bob uses the reverse. The PAKE password is exactly the eight ASCII bytes of the code. These names bind roles and context within SPAKE2 as supported by the library API. Do not replace them with hostnames.

`SPAKE2_generate_msg` must output exactly 32 bytes, transmitted using canonical RFC 4648 Base64 with padding (44 characters). Reject other lengths and encodings. For `SPAKE2_process_msg`, allocate the full `SPAKE2_MAX_KEY_SIZE` buffer and require a 64-byte result K. Do not truncate it. A successful return does not yet establish a correct password or authentication; only successful TLS using the resulting key does so. Each PAKE context is single-use and must be freed after processing the message or encountering an error.

### 3.3. Binding PAKE to TLS and confirming the key

Do not design separate MAC messages or record encryption. Use BoringSSL's RFC 9258 PSK importer:

`SSL_CREDENTIAL_new_pre_shared_key(key=K, key_len=64, id=I, md=EVP_sha256(), context=C)`

The exact input bytes are:

- I = ASCII(`d2fa-transfer/1/`) || ASCII(sid) || ASCII(`/`) || ASCII(wid)
- C = ASCII(`desktop2fa-transfer/1|D2FA-SPAKE2-BSSL1|watch-client|desktop-server|`) || raw(sid) || raw(wid) || A_msg || B_msg

A_msg and B_msg are the raw 32-byte PAKE messages, always ordered Alice then Bob. The importer performs standard key derivation and binds the result to the context and target TLS/KDF. K remains secret and is never transmitted. The numeric code must never be passed as the importer's `key` argument. `md` selects the importer hash; it does not change SHA-512 inside SPAKE2. Do not implement RFC 9258 manually when the selected API already performs it.

Each TLS object has exactly one PSK credential from this attempt. Configure no certificates or other credentials and do not enable settings that allow the client to accept certificate authentication. BoringSSL documents that this configuration requires the supplied PSK at both endpoints. With no certificates configured, certificate fallback cannot occur accidentally.

TLS profile:

| Parameter | Requirement |
| --- | --- |
| Version | TLS 1.3 only |
| Authentication | RFC 9258 imported external PSK from this exchange's K |
| Key exchange | psk_dhe_ke; fresh ECDHE using P-256 |
| AEAD | Standard TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384, or TLS_CHACHA20_POLY1305_SHA256 |
| ALPN | Exactly `desktop2fa-transfer/1` |
| Certificates or a plain PIN as PSK | Forbidden |
| 0-RTT, tickets, and resumption | Forbidden |
| Compression or custom frame encryption | Forbidden |

The profile allows three standard TLS 1.3 AEAD suites rather than assuming the public interface supports restricting negotiation to one suite. Check the negotiated result before application data. The library handles traffic keys, record sequence numbers, nonces, and integrity.

Authentication boundary: `SSL_connect`/`SSL_accept` must complete the full handshake and Finished verification. The wrapper must then confirm TLS 1.3, the required ALPN, an allowed cipher suite, and ECDHE. Only then may it send or accept HELLO, READY, or OFFER. The standard binder and Finished messages establish possession of the derived key within this TLS connection; exchanging valid PAKE points alone is insufficient. K, the roles, sid/wid, and both PAKE messages in the importer bind the TLS credential and context to this PAKE exchange rather than authorizing an unrelated channel.

This is a specific application profile combining existing mechanisms, not a published standard for the entire application. The composition requires security review before release; interoperability tests alone do not establish protocol security. Do not describe an audit of BoringSSL as an audit of this wrapper or profile.

## 4. Network, discovery, and session lifetime

The MVP requires IPv4 connectivity within one local network; the computer may use Ethernet. IPv6 is outside the MVP. The watch obtains a `Network` with Wi-Fi transport and opens the connection through that specific `Network`, rather than using the default route through a phone or LTE. If Wi-Fi is unavailable, show a clear error without falling back.

The server listens only on the user-selected LAN interface and an available TCP port assigned by the operating system. Do not automatically bind all interfaces or VPNs. Do not create UPnP mappings or require internet access. The firewall must allow the application/port on the local network. The interface need not have an RFC 1918 address; an address range is not evidence of security.

Discovery uses DNS-SD/mDNS. Service type: `_d2fa-xfer._tcp.local.`; Android NsdManager uses `_d2fa-xfer._tcp.`. Desktop: python-zeroconf. Watch: NsdManager restricted to the acquired Wi-Fi network using APIs available on the supported platform versions. Before release, determine LAN/multicast permissions for the actual minSdk, targetSdk, and Wear OS versions. Do not assume the latest Android APIs are available on the watch.

Instance name: `D2FA-` followed by the first eight characters of sid, for display only. TXT records: `v=1`, `sid=<sid>`, `auth=D2FA-SPAKE2-BSSL1`. Include no account names, code, or persistent identifiers. DNS-SD records are untrusted.

### 4.1. Plaintext startup before TLS

Before TLS, use the length-prefixed JSON framing from Section 5, but limit each frame to 2048 bytes and permit only the following message types. Every message includes `v:1`. Do not mix these with application messages.

| Direction / type | Additional fields |
| --- | --- |
| Watch → START | profile=`D2FA-SPAKE2-BSSL1`, expected_sid (32 hex characters or null), wid (32 hex characters) |
| Desktop → CHALLENGE | profile=`D2FA-SPAKE2-BSSL1`, sid, wid |
| Watch → PAKE_A | sid, wid, message (Base64 A_msg) |
| Desktop → PAKE_B | sid, wid, message (Base64 B_msg) |
| Watch → RESUME | profile, sid, wid |
| Desktop → RESUME_OK | profile, sid, wid |

With mDNS, expected_sid must equal the advertised sid. With a manually entered IPv4:port, expected_sid=null and the watch obtains sid from CHALLENGE. The watch checks the echoed wid and profile and, when using discovery, checks sid as well. CHALLENGE data remains untrusted until TLS authentication completes. Manual addressing does not require entering sid or the code on the watch.

After CHALLENGE, the watch generates and displays the code and sends PAKE_A. The desktop enables code entry only after receiving that frame. It must not send PAKE_B before the user enters the code. Once entered, it creates Bob, generates B_msg, processes A_msg, sends PAKE_B, and enters `SSL_accept`. The watch processes B_msg and enters `SSL_connect`. TLS begins on the same TCP connection immediately after the final byte of PAKE_B, without an additional textual acknowledgment. The plaintext parser must not lose TLS bytes through read-ahead; retain them in a buffer/BIO.

Do not send a plaintext "code accepted" message: only TLS determines successful authentication. An unknown profile, invalid frame, incorrect ordering, mismatched sid/wid, or invalid PAKE point closes the connection. Do not send detailed exceptions before TLS. Cancellation at this stage closes TCP and destroys the context.

Timeouts: five seconds for START, five seconds for CHALLENGE, and five seconds for PAKE_A. The desktop waits up to three minutes after receiving PAKE_A for code entry. After the user submits the code, allow 15 seconds for the remaining PAKE/TLS exchange, still subject to the watch's overall three-minute limit. Sending individual bytes periodically must not extend the complete-frame deadline. Only one pending code entry is allowed on the computer; reject additional unauthenticated connections without replacing the current entry field. The user may cancel the pending participant. This prevents attempt confusion but does not guarantee denial-of-service resistance.

### 4.2. Reconnecting within the same session

The configurator session lasts at most 15 minutes according to the computer's monotonic clock. The watch measures its own limit from START and terminates when that limit is reached if it expires first. After successful TLS, destroy the code and PAKE context. The native imported PSK credential, public sid/wid, and profile parameters may remain in RAM only until the session ends. Do not store them on disk, in the vault, or in preferences.

After an authenticated connection is lost, the watch sends RESUME with the same sid/wid. The desktop returns RESUME_OK only if it retains the credential. Both then perform a full new TLS handshake using the same imported PSK, fresh ECDHE, and Finished verification. This is not TLS session resumption and does not repeat PAKE. Plaintext RESUME_OK does not authenticate anything. Changed sid/wid or a missing credential closes the channel and requires a new manual START and code. After the new TLS handshake, the watch sends HELLO and the desktop sends READY followed by STATUS.

Automatic reconnect permits at most two attempts, two seconds apart, only after a previously authenticated connection. Never silently run another PAKE exchange. Do not create two concurrent channels for the same credential; serialize reconnection and closure of the previous channel. A different or changed address may be used only after rediscovering the same sid; TLS always authenticates the peer. An attacker without the credential cannot change STATUS results or receive OFFER.

Restarting either application, expiration, CANCELLED, or explicitly ending the session destroys the credential; another connection requires a code. On termination, close the listener, channels, and mDNS advertisement and release buffers. One session handles one batch. After a valid RESULT, STATUS may be repeated until the session ends; do not send a different new batch under the same sid/wid.

## 5. Data format and validation

Each frame inside TLS consists of a four-byte unsigned big-endian length followed by exactly that many bytes of UTF-8 JSON, without a BOM. The limit is 262144 bytes, checked before allocation. A zero length or a length above the limit closes the channel. TCP does not preserve message boundaries: reads must handle fragmented and coalesced data.

JSON must contain one object, with no duplicate keys, floating-point numbers, NaN, infinities, or invalid UTF-8. Maximum nesting depth: eight. Fields and types must match the schema; unknown fields are errors in v1. Every message includes `v:1` and `type`. Identifiers and digests use lowercase hexadecimal characters. Numeric parameters must be integers, not booleans.

A batch contains 1–50 accounts. Each account record contains exactly:

| Field | Type and meaning |
| --- | --- |
| item_id | 32 hex characters, random and unique within the batch |
| kind | Constant `totp` |
| issuer | UTF-8, 0–256 bytes |
| account | UTF-8, 1–256 bytes, not whitespace-only |
| secret | Canonical RFC 4648 Base32, uppercase, without padding |
| algorithm | `SHA1`, `SHA256`, or `SHA512` |
| digits | 6 or 8 |
| period | Integer seconds, 1–86400 |

The decoded secret must contain 1–128 bytes. Names must not contain control characters or bidirectional override characters. Treat them as plain text, without HTML/markup. Preserve valid Unicode without silently trimming or renaming. Do not automatically repair invalid records.

All parameters are mandatory. T0=0; use Unix time in seconds. HOTP and other T0 values are outside v1. An unsupported parameter rejects the batch. Do not change SHA256 to SHA1, eight digits to six, or the period to 30 seconds. If the watch does not support the full profile, block the v1 release or explicitly revise the specification. Vault passwords and vault file formats are not part of the transfer protocol.

## 6. Messages and ordering after authentication

Each table row lists fields in addition to `v` and `type`. Do not introduce other messages without changing the version.

| Direction / type | Fields |
| --- | --- |
| Watch → HELLO | session_id |
| Desktop → READY | session_id, max_accounts=50, max_frame=262144 |
| Desktop → STATUS | transfer_id, offer_sha256 |
| Watch → UNKNOWN | transfer_id |
| Desktop → OFFER | transfer_id, accounts (array of records) |
| Watch → REVIEW | transfer_id, offer_sha256, added_item_ids, skipped_item_ids |
| Watch → RESULT | transfer_id, offer_sha256, added_item_ids, skipped_item_ids |
| Desktop → CANCEL | transfer_id, reason: `user` or `locked` |
| Watch → CANCELLED | transfer_id |
| Either endpoint → PING | None |
| Either endpoint → PONG | None |
| Either endpoint → ERROR | code, transfer_id (optional), item_ids (optional array) |

transfer_id consists of 16 independently random bytes encoded as 32 hex characters. offer_sha256 is the 64-character hexadecimal SHA-256 digest of the exact OFFER JSON frame bytes, excluding the length prefix. The desktop serializes OFFER once and retains the same bytes for retries. Cross-language JSON canonicalization is unnecessary. The digest identifies retries; TLS provides authentication.

In post-TLS messages, session_id means sid from Section 3. After full authentication, the watch sends HELLO; the desktop verifies session_id and returns READY. No transfer begins before TLS completes with the required ALPN. READY must contain the exact v1 limits.

Initial submission: the desktop sends OFFER. The watch validates the entire batch and checks duplicates; any error produces ERROR without changes. A valid batch produces REVIEW and a confirmation screen on the watch. The user may approve the whole batch or cancel. The batch cannot be edited on the watch.

After local approval, the watch atomically saves new accounts and a receipt in its encrypted vault. It sends RESULT only after durable storage succeeds. There is no desktop COMMIT message: the user on the watch decides whether to save. REVIEW is not a storage acknowledgment. The desktop shows success only after a matching RESULT.

Retry: after HELLO/READY, the desktop first sends STATUS for the known transfer_id and digest. The watch returns RESULT from a saved receipt or UNKNOWN. UNKNOWN means no receipt is retained, not proof that nothing was ever saved. After UNKNOWN, the desktop may resend the identical OFFER; validation and duplicate handling prevent adding accounts again. STATUS with a known ID and a different digest produces ERROR TX_CONFLICT. STATUS responses contain no account data.

Only one OFFER may be pending. Exchange PING/PONG every ten seconds while waiting for user approval; no valid frames for 30 seconds means disconnection. The user has up to five minutes to approve, bounded by session expiration. Outside REVIEW, allow 15 seconds to receive a complete frame. Handshake and preflight timeouts are separate. Session lifetime limits do not replace network timeouts.

ERROR.code values: INVALID_MESSAGE, UNSUPPORTED_VERSION, INVALID_ACCOUNT, UNSUPPORTED_TOTP, LIMIT_EXCEEDED, DUPLICATE_IN_BATCH, NAME_CONFLICT, PARAMETER_CONFLICT, TX_CONFLICT, BUSY, VAULT_LOCKED, STORAGE_ERROR, SESSION_EXPIRED, TOO_LATE. Include item_ids only for account errors. Include no secrets, account names, file paths, or full exceptions. Parser/state errors close the channel. Validation errors end the current attempt; a corrected selection requires a new session and transfer_id.

## 7. Duplicate handling

Compare decoded secret bytes locally on the watch. Do not send a secret index to the computer.

1. Same secret, algorithm, digits, and period already in the vault: skip, even if the name differs. Do not rename the existing entry.
2. Same secret but different TOTP parameters: PARAMETER_CONFLICT; reject the entire batch. Do not guess the correct configuration.
3. Same exact issuer/account pair but a different secret: NAME_CONFLICT; reject the entire batch. The user resolves the conflict and changes the selection/name on the computer.
4. Repeated secret or name pair within the incoming batch: DUPLICATE_IN_BATCH; reject the entire batch.

Do not use a name as a persistent account identifier. A TOTP countdown or comparison of current codes is not duplicate detection. Approval adds all new accounts and skips all unambiguous duplicates. If all accounts already exist, the watch shows that result and, after approval, stores a receipt with an empty list of added accounts.

## 8. Storage, restart, and cancellation

Save the entire batch and receipt atomically in the same vault transaction. A receipt contains transfer_id, offer_sha256, added_item_ids, and skipped_item_ids, without additional secrets or names. Retain the latest 128 receipts and evict the oldest in save order. Receipts must survive restart, remain encrypted, and follow the vault's exclusion from operating system backups. Do not create a separate plaintext journal.

Without user approval, the vault must not change. Disconnection, vault locking, or restart before saving begins discards the pending batch; retry requires another approval. Do not persist pending batches in SavedState, preferences, or working files.

Accepting approval and starting the save form one serialized state transition. After this boundary, cancellation does not roll back the operation: return ERROR TOO_LATE, followed by RESULT if saving succeeds. CANCEL before this boundary discards the batch and returns CANCELLED. Closing the desktop window does not guarantee cancellation of a save already started on the watch. If CANCELLED/RESULT is not delivered, the outcome is unknown, not automatically canceled.

Local cancellation on the watch before saving directly produces CANCELLED and discards the batch; it does not require desktop approval. Local watch locking before saving produces ERROR VAULT_LOCKED and closes the channel. After saving begins, the UI no longer offers rollback; locking hides data but does not interrupt the transaction midway. After CANCELLED, another attempt requires a new session; the application must not automatically resend the canceled OFFER.

A restart during saving must leave either the old vault or the complete new vault with its receipt. Partial account imports are forbidden. If a storage operation has an uncertain outcome, reread the vault and check the receipt before reporting a result. Do not automatically claim that an exception means no changes occurred. If reliable reading is impossible, report an unknown outcome/vault error. STATUS after unlocking resolves any retained receipt.

If the vault changes between REVIEW and approval, recheck the plan under the write lock. A changed outcome requires an updated REVIEW and renewed approval; do not approve a list different from the one shown. Missing safe storage or lifecycle handling on the watch blocks transfer release.

After disconnection following a possible save, the desktop displays: "Could not confirm the result. The accounts may have been added." STATUS requires no new save approval because it changes nothing. After a desktop restart, temporary data and the PSK are lost. A new session and any re-created account selection use the duplicate rules. Do not promise automatic recovery after restarting the configurator.

## 9. Configurator-only mode

Use one transfer implementation with two account sources: selected entries from an unlocked desktop vault, or a temporary account list without a vault. The latter must not automatically create a vault, export files, autosave data, or recover sessions.

Keep secrets, the PAKE code, session keys, and OFFER only in process memory. Do not pass them through CLI arguments or environment variables. Do not log frames, otpauth URIs, QR contents, or exceptions containing input data. Do not install a TLS key-logging callback or honor SSLKEYLOGFILE in the transfer module; verify that no key logs are created. Diagnostics must exclude account contents and must not automatically upload reports containing process memory.

After success, the user ends the session and the application releases references and clears controlled buffers where possible. Apply the same cleanup after cancellation or expiration. Data must not remain in widgets, form history, or saved state. On the watch, do not save the PSK or batch in SavedStateHandle/Bundle or remembered preferences. Protect sensitive watch screens from screenshots using FLAG_SECURE; do not promise uniform screenshot blocking on all desktop operating systems.

Do not guarantee erasure of every copy from RAM, swap, hibernation, IMEs, or crash dumps. Python/JVM may copy objects. Do not describe the mode as "guaranteed to leave no traces." Secret/PSK entry fields should disable suggestions, autocorrection, and keyboard learning where supported; do not guarantee the behavior of third-party IMEs.

If the user imports an existing file, do not delete it and explain that the source file remains. When transferring from an existing desktop vault, its accounts remain there. Without a backup, the watch may become the only copy; the configurator's completion screen should remind users to retain recovery codes provided by each service.

## 10. Implementation order and acceptance criteria

1. Pass the shared BoringSSL SPAKE2 + RFC 9258 + TLS 1.3 gate (Sections 3–4) on real Wear OS hardware and each supported desktop distribution, following the test plan below. Establish minSdk/targetSdk and library versions; do not silently expand supported OS versions.
2. Share fixtures for JSON, Base32, framing lengths, malformed inputs, and RFC 6238 (SHA1/SHA256/SHA512, six/eight digits, different periods). Identical secrets and timestamps must produce identical TOTP codes at both endpoints.
3. Test transactions: disconnect before OFFER, midway through a frame, during REVIEW, during saving, and after saving but before RESULT; test restart and retry. Verify that no partial imports or repeated additions occur.
4. Test duplicates, conflicts, CANCEL before/after saving begins, concurrent vault changes, locking, and disk errors.
5. Test networking on Windows/macOS/Linux: firewalls, multiple interfaces, absent mDNS and manual addressing, client isolation, Wi-Fi without internet, and a powered-off phone. Verify that the watch uses neither a phone proxy nor LTE.
6. Check logs, working files, UI state, and backups for secrets/PSKs. Simulate malicious mDNS servers, incorrect PSKs, malformed preflight messages, and oversized frames.

Do not release transfer before the required watch stabilization work is complete: validation, TOTP conformance, transactional storage, locking, and lifecycle handling. Similar vault formats do not establish interoperability for this protocol.

Outside the MVP: Bluetooth, phones, cloud services, persistent pairing, synchronization, account replacement, whole-vault import, concurrent transfers to multiple watches, configurator recovery after restart, IPv6, QR, and support for public networks that block local traffic.

## 11. Official sources and verification limits

Documentation checked on 2026-10-31. Sources establish available mechanisms/APIs; profile parameters and their composition are project decisions. The Python ssl and Bouncy Castle references below concern earlier candidates, not the selected v0.3 transport stack.

- TLS 1.3, PSK, psk_dhe_ke, Finished, and 0-RTT: https://www.rfc-editor.org/rfc/rfc8446.html
- External PSK entropy requirements and the need for PAKE with weak codes: https://www.rfc-editor.org/rfc/rfc9257.html
- Python ssl (PSK API added in 3.13): https://docs.python.org/3.13/library/ssl.html
- Current Python notes on SHA-256 and unintended certificate fallback: https://docs.python.org/3/library/ssl.html#ssl.SSLContext.set_psk_server_callback
- Bouncy Castle TLS getExternalPSKs: https://downloads.bouncycastle.org/java/docs/bctls-jdk18on-javadoc/org/bouncycastle/tls/TlsClient.html
- Bouncy Castle BasicTlsPSKExternal: https://downloads.bouncycastle.org/java/docs/bctls-jdk18on-javadoc/org/bouncycastle/tls/BasicTlsPSKExternal.html
- Bouncy Castle versions and PSK modes: https://downloads.bouncycastle.org/java/docs/bctls-jdk15to18-javadoc/org/bouncycastle/tls/AbstractTlsPeer.html
- Direct Wear OS networking and default phone proxy behavior: https://developer.android.com/training/wearables/data/network-communication
- NsdManager, version constraints, multicast, and local access: https://developer.android.com/reference/android/net/nsd/NsdManager
- python-zeroconf: https://python-zeroconf.readthedocs.io/en/latest/api.html

Additional PAKE references:

- SPAKE2 and mandatory key confirmation: https://www.rfc-editor.org/rfc/rfc9382.html
- BoringSSL SPAKE2 API and its draft-based variant: https://github.com/google/boringssl/blob/main/include/openssl/curve25519.h
- Limitations of depending on BoringSSL: https://github.com/google/boringssl
- python-spake2 and its timing-attack warning: https://github.com/warner/python-spake2
- spake2-java, BoringSSL compatibility, and its unaudited status: https://github.com/MuntashirAkon/spake2-java

No implementation, interoperability test, or audit of the watch code has been performed. This document does not assert the security of the existing applications.

## 12. Proof-of-concept and test plan — implementation agent handoff

Do not immediately build the full UI or import real accounts. Build an isolated experiment in the repositories only after the user authorizes implementation. The following are acceptance criteria, not completed test results.

| Stage | Test | Expected outcome |
| --- | --- | --- |
| T0 | Fetch the pinned revision, confirm APIs, review upstream fixes | Identical sources and configuration at both endpoints; record SHA and toolchains |
| T1 | Upstream BoringSSL tests and wrapper sanitizers | No tool-reported crashes, leaks, or undefined behavior; no algorithm changes |
| T2 | Python/CFFI ↔ Kotlin/JNI, 1000 exchanges with correct codes and fresh sid/wid | Matching full K in tests only, followed by successful TLS and a nonsecret marker transfer |
| T3 | Leading zeros, UTF-8/ASCII/NUL handling, reversed roles or one changed endpoint name | Leading zeros work; byte/role mismatches fail authentication |
| T4 | Incorrect code, modified A_msg/B_msg, importer context, sid/wid, or PSK | TLS never reaches authenticated state; no OFFER or account names |
| T5 | Certificate attempt, TLS 1.2, missing PSK, wrong ALPN, 0-RTT, psk_ke without ECDHE | Rejection without fallback |
| T6 | MITM proxy: modification, replay, and mixing concurrent sessions | No false authentication or disclosure; a transparent relay may forward encrypted bytes but cannot read them |
| T7 | Full reconnect after disconnection without a second code, and reconnect after restart | Retained credentials and STATUS work before restart; restart requires new PAKE |
| T8 | Expiration, three manual attempts, timeout in every state | No automatic code-limit resets or context reuse |
| T9 | Invalid lengths, UTF-8, Base64, points, duplicate keys, and fragmented/coalesced frames | Controlled rejection, bounded memory, no crashes |
| T10 | Windows/macOS/Linux, installation without a compiler, and a real Wear OS device | Shipped binaries work without private APIs, ADB, a phone, or internet |
| T11 | Vault integration and disconnection/restart during saving | Atomicity and duplicate criteria from Sections 7–8 hold |
| T12 | Release packaging, JNI/CFFI, logs, and lifecycle | No keys in logs/persistent state; a closed session cannot be restored |

On a real watch, measure at least 100 authentications: median/p95 PAKE+TLS computation time excluding code entry, errors, memory usage, and behavior on screen timeout/locking. Product target: p95 within three seconds after code submission on the selected test model. This is a measurement target, not a performance promise. Report the exact watch model and Wear OS/API version.

The report must mark each T0–T12 item PASS, FAIL, or NOT RUN, with evidence, application/library/OS versions, and hardware limitations. Do not mark macOS, Windows, or Wear OS as PASS based only on Linux testing. Missing hardware means NOT RUN and an open acceptance criterion, not a substitute compatibility claim. Timing measurements or 1000 successful exchanges do not prove constant-time execution or complete security.

Release requires passing tests on supported platforms, completing the watch stabilization dependencies, and reviewing the security of the wrapper and SPAKE2/importer/TLS composition. Do not change the algorithm/profile while fixing tests without a separate specification revision.

Additional sources for the v0.3 decision:

- BoringSSL TLS importer and PSK-only behavior: https://commondatastorage.googleapis.com/chromium-boringssl-docs/ssl.h.html
- Importer standard: https://www.rfc-editor.org/rfc/rfc9258.html
- Confirmed baseline revision: https://chromium.googlesource.com/external/gob/boringssl/boringssl/+/594a44291086dcc7cef06cabec4c0b47afd674c0
- BoringSSL build instructions: https://boringssl.googlesource.com/boringssl/+/HEAD/BUILDING.md
- spake2-c source containing rand(): https://github.com/MuntashirAkon/spake2-c/blob/master/spake2.c
- Wrapper dependency reference: https://github.com/TheZ3ro/spake2-cffi/blob/main/.gitmodules
