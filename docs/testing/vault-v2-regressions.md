# Watch v2 storage codec regressions — 2026-11-12

Scope: first implementation slice of the [accepted v2 design](../transfer/vault-v2-proposal.md).
Separate v1/v2 payload codecs, v2 header AAD, receipt-boundary validation,
defensive snapshots and receipt-preserving manual additions are implemented.
This change adds no migration entry point, safety-copy implementation, receiver,
network permission, JNI or transport. `Vault.create()` still creates v1.

## Checks

The initial focused command passed 11 tests (9 `VaultV2Test`, 2
`StrictVaultJsonTest`), without failures or skips:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*VaultV2Test' --tests '*StrictVaultJsonTest' --stacktrace
```

The first full JVM run completed 354 cases with three failures and one expected
opt-in Python bridge skip. Failures were historical tests treating version 2
as unsupported, now replaced with unsupported version 3/99. The tests still
reject v1-header/v2-payload mismatches and preserve source files; tampered v2
headers fail authentication. Those initial failures are not reported as passes.

Final verification passed **356 tests, zero failures/errors/skips**, including
the actual pinned Python bridge and the added explicit JSON type checks.
The debug APK built successfully. The verified implementation commit is
`249bde8` (the following report update changes documentation only). Command:

```powershell
python tools/interop/run.py --desktop-source ..\..\app\build\desktop-pinned
```

This runner executes `:app:testDebugUnitTest :app:assembleDebug --stacktrace`
through the repository wrapper, verifies the immutable corpus, enables the
actual pinned Python bridge, and fails if that bridge is skipped.
Source hashes, Python 3.13.11 and pinned dependencies were verified before run.

## Regression coverage

- Ordinary v1 unlock/manual save retains header 1 and exactly the original root
  schema; supported Python-written v1 files and RFC/boundary TOTP tests remain
  in the complete suite.
- V2 round trip/manual addition preserves historical key text, nullable names,
  duplicate labels, seven-digit/non-default TOTP parameters and receipts.
  Receipt changes invalidate the candidate baseline. Captured receipt lists
  cannot be changed by the caller or by a later candidate writer.
- Wrong password and changes to magic/version/salt/nonce/ciphertext/tag reject
  without modifying file bytes. Exact header AAD is required.
- Malformed v2 payload/versions, unknown/missing fields, repeated/escaped keys,
  invalid UTF-8, non-integer numbers and excessive nesting fail explicitly.
- Receipt count/IDs/hash/item limits and disjointness are enforced before file
  mutation. The maximum valid 128-receipt list round-trips in order.
- Injected move failures before and after publication produce conservative
  uncertainty, leaving either the complete old account/receipt snapshot or the
  complete new one. The old encrypted backup is preserved. Existing full
  replacement/fault tests cover the unchanged writer's other stages.

## Limits and remaining gates

No automatic migration or release-ready durable import is claimed. Receipt
insertion/eviction, duplicate planning, migration faults, recovery safety-copy
inventory, actual process death/power loss, backup/device transfer, Android
lifecycle and physical-device v2 behavior require later integration tests.
No full-volume/OOM or directory-sync guarantee is established. Core tests do
not authenticate network peers or audit the native profile composition.
The unchanged supplied transport specification's T0 is incomplete and T1–T12
are NOT RUN; these codec tests do not mark T11 as passed.

## Review follow-up

PR #67 review requested useful parser failure attribution. Load errors now carry
only a fixed diagnostic phase (UTF8, JSON_SYNTAX, JSON_TYPES, MODEL_SCHEMA or
MODEL_VALIDATION). Original parser exceptions are deliberately not chained:
their messages may include decrypted input. A regression checks phase attribution
and the entire rendered exception chain for synthetic account/key markers.
The payload serializer also derives its version from the validated snapshot.
The previously recorded 356-case result precedes these follow-up changes;
the full pinned Python/Kotlin runner passed **357 tests, zero failures/errors/skips**,
including the new diagnostic-chain regression, and rebuilt the debug APK.
