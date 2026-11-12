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

Full JVM/actual pinned Python interoperability/APK checks are pending at this
report update. Desktop transfer code has not run the new shared corpus yet.
Physical/device transaction and lifecycle gates remain open; this is no audit.
The proposed extension from bidi overrides to all directional controls awaits
owner clarification and is not silently enabled.
