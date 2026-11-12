# Explicit v1 → v2 migration backend — 2026-11-12

Scope: backend only, following the [accepted storage design](../transfer/vault-v2-proposal.md).
There is **no application caller, automatic migration or consent screen**. Do not
expose it until archive inventory/read-only recovery, session writer ownership,
locking, lifecycle and required Android/device durability tests are integrated.

## Implementation

The caller supplies `CONFIRMED` per-user consent and an immutable baseline,
holds the existing process-wide writer mutex, and enters its owned,
non-cancellable mutation boundary. The helper itself is synchronous; it does
not provide multi-process locking or independently enforce Android lifecycle.

It requires clean existing storage, authenticates the exact bounded v1 ciphertext
to be copied, and rejects a changed baseline. Under the supplied Android
`noBackupFilesDir`, it exclusively creates `transfer-migration/vault-v1.d2fa`,
writes/flushes/syncs/closes it, and verifies its byte count and SHA-256 locally.
It rechecks the source before publishing v2 with unchanged account fields and
empty receipts through the existing atomic writer. No plaintext journal, password
cache, rollback, automatic deletion, copy replacement or blind save retry is added.

After publication uncertainty it authenticates/rereads main. A verified v2
snapshot can establish a logical commit; exact original v1 bytes can establish
non-commit when no commit was previously known. Failed verification cannot
reverse a known commit. Artifacts/uncertain verification require storage review
and never return an active writable vault. The caller must check `requiresReview`,
not just the raw writer outcome enum. NOT_COMMITTED refers to publication of
the main v2 vault, not absence of filesystem changes: the safety copy and
temporary/backup candidates may already exist.

## Executed checks

Windows repository wrapper, synthetic data only:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*VaultMigrationTest' --stacktrace
python tools/interop/run.py --desktop-source ..\..\app\build\desktop-pinned
```

- Focused backend suite: **10 passed**, no failures/skips.
- Full runner: **367 passed**, zero failures/errors/skips, actual pinned Python
  bridge and debug APK build passed. Python 3.13.11, Desktop commit
  `7d1089d6bee0f3f82678ca8a13988c742840fba0`, immutable source/corpus/dependency
  pins checked. Implementation content is recorded in commit `0c98012`.
- The actual Desktop library rejects a migrated Watch v2 file with
  `UnsupportedFormat`, preserving its bytes, and reads the retained v1 copy with
  complete field equality and the fixed-time TOTP vectors. The two existing v1
  round trips and all immutable corpus checks still pass. No Desktop source or
  v1 fixture was modified.

Fault cases cover missing consent (no I/O), wrong password, stale/v2 baseline,
existing partial copy, unresolved temp/backup, copy create/write/flush/sync/close
failures, corrupted copy, a source changed after copying, move uncertainty before
and after publication, late exceptions after clean commit, and unreadable
verification after known/uncertain commit. Original bytes and retained recovery
candidates are checked, not automatically discarded.

## Limits

The retained archive is not yet integrated into application startup/recovery.
Before exposure, a missing main plus archive must block empty setup and offer
explicit authenticated read-only recovery; a failed migration must not permit
automatic retry or archive overwrite. The archive must not block ordinary clean
v2 use after a completed migration, and it must never be promoted automatically.
These states still need integration tests.

No Android cloud/device-transfer, physical-watch, actual process-death/power-cut,
full-volume/OOM, directory-sync or lifecycle claim is made. File syncing and
atomic rename tests alone do not prove physical durability. A caller must pass
the real no-backup directory; synthetic JVM directories do not verify Android
backup behavior. The copy can be lost with app-data deletion/reset/uninstall,
requires the original password and omits later additions. Keep independent
service recovery methods. No real vault or secret was used.
