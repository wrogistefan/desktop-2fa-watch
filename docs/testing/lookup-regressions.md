# Unambiguous name lookup (#35)

Baseline: `d9cc69c` (#56), branch `fix/35-unambiguous-account-lookup`.
Only public synthetic entries are used; no vault format, IDs, UI or cryptography
is changed.

`getEntry` now chooses the first non-empty **tier**, never a first matching row:
exact case-sensitive account names, then issuer fallback. Multiple matches at
that tier throw `AmbiguousEntryException` (an `IllegalArgumentException`);
missing matches retain the general exception type with a generic message.
`removeEntry` resolves before mutation and preserves every entry on ambiguity.
Instance synchronization coordinates these methods with existing synchronized
instance mutations; it does not promise safety for arbitrary external mutation
of the public model or replace the B5 process-wide writer boundary.

The rules were compared against actual pinned Desktop v0.10.0
`Vault._matching_entries/get_entry/remove_entry` at
`7d1089d6bee0f3f82678ca8a13988c742840fba0`. Desktop uses the same account-name
precedence and ambiguity rejection. No Desktop multi-process locking or stable
ID scheme is copied. Future transfer still uses explicit E2 collision decisions,
not an implicit name selector.

Seven JVM regressions cover reordered/rotated lists, account/issuer overlap,
multiple exact names despite a unique issuer, duplicate issuers, fully identical
copies, null/empty labels, case-sensitive missing matches and correct removal.
Ambiguous/missing removal must leave the original ordering and contents intact.
Assertions omit account keys/code/password values.

On 2026-11-09 the full runner passed **343 JVM tests, zero failures/errors/skips**,
including existing RFC/crypto/storage/session tests and actual pinned Python
interoperability. Debug APK assembly passed:

```powershell
$env:GRADLE_USER_HOME='C:/Users/lukas/.gradle'
python tools/interop/run.py --desktop-source app/build/desktop-pinned
```

The runner invokes the repository Wrapper's full unit/debug tasks and checks
that the actual-Python method ran. Existing duplicate/null fixtures still load
and round-trip. No new emulator/physical test is claimed for this library-only
change; it does not establish hardware behavior or a security audit.
