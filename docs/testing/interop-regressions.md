# Actual pinned Desktop/Kotlin interoperability (#29 / D2)

Baseline: `4d72cc8` (#54), branch `test/29-actual-python-kotlin`.
Only the public synthetic #28 corpus and disposable temporary files are used.
No production Kotlin, vault format, KDF, network permission, receiving endpoint
or user data is changed. Whole-vault UI remains #10; account-package semantics
are separate from the internal encrypted file format.

## What is exercised

The source-verified Desktop v0.10.0 Vault/models/generator at
`7d1089d6bee0f3f82678ca8a13988c742840fba0` execute through the Python bridge, using
Python 3.13.11 and the ten pinned dependency versions. This is not an independent
Python reimplementation of Kotlin's cryptography. Pins/hashes are checked before
import; no live Desktop install or real vault is accessed.

- All 13 supported entries retain every field, ordering, duplicate/null names,
  Unicode and original valid key text through Kotlin save/reload and both actual
  cross-language paths: Python → Kotlin → Python and fresh Kotlin → Python → Kotlin.
- Each generator compares the 117 timestamp/entry expectations (all algorithms,
  6/7/8 digits and 30/45/60-second periods) on the supported data. This complements
  the independent RFC suite and does not replace it.
- Both actual loaders exercise the 23 manifest scenarios, including explicitly
  unsupported historical data, unknown/missing/invalid versions, wrong password,
  tamper/truncation and malformed data. Rejection must leave source hashes intact.
- Deterministic at-limit/overflow files are materialized in temporary storage.
  The at-limit data intentionally fails authentication; it is not evidence of a
  valid maximum-size encrypted round trip. No large fixture is committed.
- Plain JVM runs check hashes, Kotlin compatibility and negative preservation.
  Actual Python execution requires the runner; a missing/skipped bridge fails
  its post-run JUnit XML check. Mode/scripts/pins are tracked Gradle test inputs.

Python exceptions and JVM assertion messages omit secret/password/code values.
Already-known unsupported records are preserved and rejected according to the
manifest; this work does not normalize or repair them for Watch.

## Command and limitations

```powershell
$env:GRADLE_USER_HOME='C:/Users/lukas/.gradle'
python tools/interop/run.py --desktop-source app/build/desktop-pinned
```

The local source directory contains the 15 hash-verified files fetched from the
exact public Desktop commit. For normal reproduction use the checkout/venv
instructions in the [corpus README](../../app/src/test/resources/interop/README.md).
The runner invokes the full JVM suite and debug APK assembly, then checks that
the actual Python method passed.

On 2026-11-09 this command completed successfully in 5m12s: **336 JVM tests,
zero failures/errors/skips**, and debug APK assembly passed. All three
`PinnedInteropTest` methods passed, including the actual Python bridge; the
runner's XML post-check confirmed that method was present and not skipped.
The actual bridge checked 23 Desktop load outcomes, 117 fixed timestamp/entry
expectations and two cross-language paths. Kotlin separately checked all Watch
outcomes and returned Python files, preserving original source hashes. The full
existing RFC, password/tamper, storage-fault and session tests ran in that suite.
The stdlib preflight verified all 22 artifact hashes and both size recipes.

CI checks out the full immutable commit and uses the same Python/dependency
pins. Wrapper network timeout is raised to 60 seconds after prior external
distribution timeouts; URL, distribution checksum, versions and app security
settings remain unchanged. External download failures are not passing tests.

These checks do not establish physical KDF/Keystore/lifecycle behavior, delivery
protocol security, maximum-size valid-vault interoperability, power-loss durability
or an independent audit. Required physical measurements/evidence remain #30/#37.
