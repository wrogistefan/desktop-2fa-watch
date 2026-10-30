# Vault and Password Cache Regression Checks

Date: 2026-10-30. Scope: local fixes for #4/#5/#6, without transfer/network code
or a release. All examples and fixtures are synthetic.

## Changes and threat analysis

- Cache write/removal results reach the UI separately from successful vault
  operations. Lock detaches the active vault immediately even if cleanup fails.
  Deleting all secure/legacy preference stores does not need a working decryption
  key. Failure warns that automatic unlock after restart may still be possible.
  Manager operations are serialized; unlock submission waits for Lock cleanup.
- Uncertain storage inspection is not evidence of a fresh install. Only confirmed
  absence allows setup. A remaining `.bak` blocks setup rather than being ignored.
  A new file is opened with atomic `CREATE_NEW`, then written and synced;
  competing destinations are never replaced. A failed first write may leave a
  partial file, which deliberately blocks another setup rather than being reset.
- Unknown JSON fields are rejected before a writable vault is exposed. This
  trades forward readability for preventing silent metadata loss. Supported v1
  data needs no migration; unsupported originals remain intact.
- Manual additions prefer the password that actually unlocked the session, so
  an unsuccessful cache update cannot select a stale cached password for saving.

The encrypted layout, Argon2id parameters, fresh salt/nonce behavior and AES-GCM
authentication are unchanged. These changes do not prove security: update-save
fallbacks, directory-sync/power-loss durability, automatic recovery, full input
validation and late coroutine/lifecycle races still require their roadmap work.
No automatic lock, per-use device authentication or secure memory-erasure claim
has been added. The shared AGENTS security standard is unchanged.

## JVM cases

`PasswordCacheTest` covers unavailable/read-failing storage, commit=false, every
store's deletion failure/exception, continued cleanup after failure, cleanup
without decrypting and a synthetic round trip.

`VaultFilesTest` covers absent/present/backup states, denied/failed inspection,
invalid paths, a competing file appearing after inspection and a write failure
after partial creation. Assertions verify preservation of existing destination
bytes and refusal to restart setup over an interrupted new file.

`VaultTest` adds create-only save/load, refusal of existing/recovery files, unknown
root/entry fields, nullable/duplicate parameter-preserving round trips, wrong
password, modified authentication tag and truncated input, including unchanged
source bytes after errors. Existing wizard/password tests remain in the suite.

## Android cases and isolation

`VaultSafetyDeviceTest` tests real encrypted preference save/read/clear, corrupted
synthetic preference keysets and both legacy stores, plus `CREATE_NEW` writing
and refusal to replace a competing destination on Android's filesystem.

Its context prefixes preference names (including application-context access),
never reads the normal vault or removes any Keystore key, and uses only temporary
synthetic files in app cache. A corrupted preference keyset is not the same as
physical Keystore key invalidation. Hardware-backed protection is not assumed.

## Commands and results

An initial Android run passed both cache tests but rejected the proposed
hard-link publication with `AccessDeniedException`. The implementation was
changed to `CREATE_NEW`; it no longer depends on hard links. Final results are
recorded below. Earlier UI reports remain historical evidence only.

| Command | Final result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest --stacktrace` | Passed: 31 tests, no failures/errors |
| `.\gradlew.bat :app:assembleDebug --stacktrace` | Passed |
| `.\gradlew.bat :app:assembleDebugAndroidTest --stacktrace` | Passed during initial verification; final test APK rebuilt by connected tests |
| `.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | Passed: 3 tests on Wear_OS_Large_Round, Android 17 / API 37 |
| `git diff --check` | Passed |

The connected run used `ANDROID_SERIAL=emulator-5580`. The existing AVD was
started with `-read-only -no-snapshot -no-window -no-audio`; no existing AVD data
was intentionally modified or snapshotted. The test emulator was stopped after
verification. No physical watch or new manual UI pass was used. Gradle used the
repository's Java 25 daemon criteria; launcher Java was 21. No SDK, dependency,
KDF or vault version was changed. Instrumentation runner selection was added to
the app's test configuration.

## Remaining device checks

Test key loss/invalidation and device lock on disposable hardware; check immediate
Lock and warnings in the UI, background/resume, process death, late coroutine
completion, fullscreen IME and restart after a failed clear. Do not substitute
JVM mocks or a basic preference test for these cases. Do not reset personal app
data to exercise recovery. Full Python-library interoperability is not established
by the local Kotlin round-trip tests.
