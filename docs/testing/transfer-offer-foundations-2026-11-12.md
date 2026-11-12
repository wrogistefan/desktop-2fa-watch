# OFFER validation foundations — 2026-11-12

Offline code only; no socket, account reception, migration, save or UI integration.

Focused Wrapper command with the existing SDK explicitly selected:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'org.desktop2fa.desktop.watch.transfer.TransferOfferTest' --stacktrace
```

**11 tests passed**, including the shared UTF-8/digest/schema corpus, supported
TOTP combinations, Base32 limits/pad bits, Unicode byte length, raw surrogate
escape rejection, input mutation, stale baselines, historical conflicts in both
orders, all-duplicate receipt, idempotent lookup, digest conflict and FIFO 128.

An initial test accidentally serialized an in-memory unpaired surrogate through
UTF-8 replacement before parsing, so it tested a valid replacement character.
The regression now sends the literal JSON surrogate escape bytes; the focused
rerun passed. Production decoding continues to reject malformed UTF-8.

Full `python tools/interop/run.py --desktop-source ../../app/build/desktop-pinned`
passed: **378 JVM tests, zero failures/errors/skips**, actual pinned Python bridge
and debug APK included (3m 24s). The shared corpus contains 24 exact-byte vectors.
These are local checks; CI still needs to verify the published branch. Desktop transfer code has not run the new shared corpus yet.
Physical/device transaction and lifecycle gates remain open; this is no audit.
The proposed extension from bidi overrides to all directional controls awaits
owner clarification and is not silently enabled.

## Review follow-up

The storage readiness guard now reports typed STORAGE_ERROR with no cause for
v1 or invalid snapshots, rather than leaking IllegalArgumentException to a
caller handling transfer failures. It does not change legacy vault validation
or migrate a valid v1 snapshot. The added regression checks both prepare/status,
unchanged historical seven-digit data and absence of diagnostic causes.

Full local rerun after this change passed **379 JVM tests, zero failures/errors/
skips**, actual pinned Python bridge and debug APK included. It completed in
13m 26s under local build load; this is not a Watch performance measurement.
CI must verify the updated PR head independently before merge.

## 2026-11-13 review clarification

Move the v2 prerequisite outside the payload-validation catch; only model
validation errors are translated there. Document STORAGE_ERROR as the existing
wire code for unavailable/invalid storage, never an automatic retry or migration
instruction. Do not invent a new wire code without a versioned spec change.
Added unchanged-snapshot regressions for duplicate receipt IDs, malformed digest
and empty receipt item lists. Final focused run passed **13 tests, zero failures/
errors/skips**. Fresh CI must verify the complete updated suite; the earlier
379-test full run remains evidence for the preceding revision only.
