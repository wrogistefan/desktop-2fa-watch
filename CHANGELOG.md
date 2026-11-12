# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Isolated transport experiment

- Add a standalone synthetic Watch Wi-Fi smoke APK and Python/CFFI peer using
  the approved native PAKE/importer/TLS profile. Keep the product app offline;
  no vault/account access or receiver UI is enabled. Record host/Android build
  evidence and required physical-watch gates before real-secret reception.

### Storage foundations

- Discover retained Watch migration copies during startup/recovery, block empty
  initialization if main is missing with migration artifacts, and offer explicitly
  selected password-authenticated read-only v1 viewing after unlock errors.
  Keep healthy main unlock behavior and leave all recovery files untouched.


- Add an explicit-consent migration backend that retains/verifies an encrypted
  v1 safety copy and reconciles uncertain v2 publication by authenticated read.
  No app entry point or automatic migration is enabled. Archive recovery,
  lifecycle/consent UI and device durability checks remain prerequisites.

- Add a separate Watch vault v2 codec with authenticated header and strict
  encrypted receipt validation. Preserve v1 output and existing v2 receipts
  through manual additions, including stale-baseline checks. No automatic
  migration, transfer receiver, network permission or migration UI is enabled.

### Documentation

- Import the owner-provided transfer specification v0.3 and document the accepted
  Watch v2 storage/receipt, opt-in migration and recovery design. Explain the
  distinction between transfer wire compatibility and direct vault-file
  compatibility with Desktop. No receiver, format migration or v2 implementation
  is included in this documentation change.

## [0.3.0] - 2026-11-11

Local foundations for future transfer; no transport or receiver is implemented. Vault v1 and KDF parameters are unchanged. Keep the existing vault password: remembered-password auto-unlock has been removed. No automatic vault migration is required. Roadmap #13 and outstanding device/platform checks remain open.

### ✨ Changed

- Request secure-window capture protection on every app screen and disable
  recents screenshots on API 33+. Document capture/accessibility tradeoffs and
  independently unverified OEM/ambient behavior (#27).

- Resolve exact account names before issuer fallback; reject ambiguous lookup or
  removal without changing data, independent of entry order. Preserve historical
  duplicate/null records without an ID or format migration (#35).

- Lock on screen off/device lock, Activity stop or 60 seconds without user input.
  Pause/focus loss hides codes without discarding an unexpired editing session;
  native IME edits renew the deadline. Returning always requires a password
  after a genuine lock. Refresh TOTP from current wall time on resume (#26).

- Route manual and internal batch additions through one lifecycle-owned commit
  operation; validate the entire privately copied candidate, reject stale data
  and write all additions once. No batch receiver or vault migration is added (#36).

- Require explicit vault-password sessions for new and existing users; remove
  Remember and cached auto-unlock. Startup/Lock remove old credential stores;
  failed cleanup warns about leftover data and older builds, without resetting
  the vault. Keep the original password before updating (#24/#25).

- Replace the account form with a three-step wizard: service name, account name,
  and setup key, each with a heading and one input per screen.
- Add Back/Cancel, secret visibility, keyboard submission, and validation after
  keyboard dismissal. Disable autofill and request no suggestions.
- Accept whitespace-formatted and unpadded Base32 keys, validate decoding and
  trailing bits, and store canonical padding for desktop-compatible readers.
- Guard repeated saves, retain drafts on errors, and scroll to the new account
  with confirmation on its card.

### 🧪 Testing

- Record physical SM-L705F evidence: 16 core cases passed and one emulator-only PIN case skipped; five KDF runs (90 samples), native Samsung keyboard, device Lock, recents and AOD observations. Include real-save/Lock record preservation and a bounded partial-write failure model. Actual OOM/full-volume and broader platform coverage remain unverified (#64).

- Record 15 integrated Wear emulator regressions for lock, actual owned-key loss,
  native fullscreen IME, late I/O and process termination. The later physical
  evidence records the completed subset and remaining limitations; #37 stays open.

- Add a pinned actual Desktop Python ↔ Kotlin runner for fields, ordering,
  duplicate/null names, fixed-time TOTP codes, safe rejection and both round-trip
  directions; CI uses pinned Python/dependencies and verifies the bridge ran (#29).

- Add immutable public synthetic fixtures pinned to Desktop v0.10.0 and full
  commit, source/dependency hashes, deterministic reproduction and explicit
  historical/negative/size-limit expectations. Cross-language execution remains
  separate (#28/#29); no vault format or KDF migration is introduced.

- Verify all RFC 6238 vectors for three algorithms, 6/7/8 digits, leading zeroes,
  independent boundary/large-counter cases and controlled countdown rollover (#16).
- Cover Base32 normalization/padding, invalid input, wizard navigation, duplicate
  names, save claiming, and retries with unit tests.
- Validate wizard entry, native Samsung keyboard actions, draft retention,
  duplicate-name checks, normalization, save/cancel, legacy entries, and locking
  on a physical Galaxy Watch Ultra; add screenshots and a device report.
- Samsung's fullscreen editor still hides the wizard heading despite IME hints.

### 🐛 Fixed

- Reconcile actual window focus before input and I/O publication after Activity
  recreation, so an explicitly reopened vault can display codes again. Keep
  pause/focus-loss hiding and all session-lock triggers (#37).

- Invalidate session ownership before Lock/disposal, reject late I/O publication,
  clear sensitive drafts, serialize old/new Activity I/O, and require storage
  review for writes completed after locking. Add Lock during loading/saving (#25).
- Reject stale manual-add snapshots without replacing newer accounts; retain
  non-secret review outcomes across Activity recreation until explicit Retry,
  and show recovery guidance after 30 seconds of pending I/O (#25).

- Distinguish unavailable remembered credentials from an empty cache at startup,
  preserve manual password recovery, and exclude all three known credential
  stores from backup/transfer; exercise immediate Lock and owned key loss (#23).
- Refuse symlink-following vault opens and changed recovery selections; tighten
  the debug process-test directory boundary (review follow-up to #21/#46).
- Report explicit update/verification outcomes and require storage review after
  uncertain or post-commit errors instead of inviting duplicate additions (#22).
- Detect backup/fixed/legacy pending writes at startup and offer authenticated
  read-only code recovery without replacing or deleting any candidate (#21).
- Require atomic existing-vault replacement without a non-atomic fallback or
  destructive restore; retain failed-write evidence and test I/O faults (#20).
- Keep startup routing independently testable: only confirmed absence allows
  setup; cache/load failures require unlock and cancellation is propagated (#19).
- Enforce the vault read limit on bytes actually consumed, including files that
  grow after the size check; close streams and reject partial/oversized input (#18).
- Require explicit integer payload version 1 and reject unsupported write
  snapshots; preserve rejected version/extension data without silent migration (#17).
- Report controlled TOTP generation failures and render an unavailable state
  without a code/countdown, instead of substituting SHA1/30 seconds or dashes (#15).
- Share ASCII Base32 and TOTP parameter validation across wizard/load/add/save,
  reject invalid snapshots before I/O, and preserve valid historical key text,
  nullable names and duplicate labels (#14).
- Surface password-cache write and deletion failures, preserve immediate Lock,
  and clear unreadable secure/legacy stores without decrypting them (#4).
- Refuse setup on uncertain storage or leftover recovery backups, and publish
  new vaults without replacing a competing existing file (#5).
- Reject unknown vault/entry JSON fields before modification instead of losing
  them on save; supported v1 vaults need no migration (#6).
- Keep input text readable on the dark wizard background in error/disabled states.

## [0.2.0] - 2026-10-28

### 🆕 Added

- Wear OS MVP workflow: create and unlock a vault, manually add TOTP accounts,
  display live codes and countdowns, and lock the vault.
- Optional password storage in encrypted preferences protected by Android Keystore.
- Native Wear OS `TransformingLazyColumn` account list with rounded cards.
- User, security, cryptography, UX, compatibility, and development documentation,
  with emulator screenshots captured using synthetic accounts.

### 🐛 Fixed

- List keys no longer collide for duplicate account names or secrets, including
  unnamed entries loaded from legacy vaults.
- List header and footer now each have a single layout container, keeping the
  title and Add Account / Lock Vault actions visible and reachable.
- Non-positive TOTP periods use a 30-second UI fallback to avoid division by zero.
  Strict validation remains planned in issue #10.

### 🛡️ Security

- Lock clears active UI state and requests credential-cache removal off the main
  thread; plaintext fallback credential stores are removed when clearing.
- Credential preference initialization handles unavailable/corrupted storage and
  attempts preference recreation before falling back to manual unlock.
- Android backup/transfer rules exclude the vault and credential preferences.

### 📦 Versioning

- Bump `versionName` from `0.1.0` to `0.2.0` and `versionCode` from 2 to 3.
  The minor increment reflects new MVP functionality during pre-1.0 development.
- The `.d2fa` binary format and JSON payload version remain 1.
- Full desktop interoperability verification and watch import/export are deferred
  to [issue #10](https://github.com/wrogistefan/desktop-2fa-watch/issues/10).

## [0.1.0] - 2025-02-19

### Added
- Core encrypted vault storage matching desktop-2fa binary file format (`D2FA` magic header + version `0x01` + 16-byte salt).
- Argon2id password-based key derivation (`t=4`, `m=128MiB`, `p=2`) via Bouncy Castle.
- AES-256-GCM authenticated encryption and decryption utilities.
- Pydantic-compatible `TotpEntry` and `VaultData` models with `kotlinx.serialization`.
- Vault lifecycle management (create, load, save, addEntry, getEntry, removeEntry) with atomic file updates and `.bak` recovery.
- Robust error handling hierarchy (`InvalidPasswordException`, `CorruptedVaultException`, `UnsupportedFormatException`, `VaultIOException`).
- Unit test suite (`VaultTest`) covering persistence, crypto, and versioning.
- GitHub Actions CI pipeline (`Android CI`) for automated testing and building.
- Initial Wear OS UI integration in `MainActivity` with vault initialization action.
