# Development & Testing

## Build requirements

- Android Studio with support for the repository's Android Gradle Plugin 9.4.1.
- Android SDK Platform 37 and the SDK components requested by Gradle.
- Java 25 for the Gradle daemon, as specified in
  `gradle/gradle-daemon-jvm.properties`. The Foojay resolver can provision the
  configured toolchain when network access is available.
- The checked-in Gradle 9.6.0 wrapper; Java source/target compatibility remains 11.
- A Wear OS device/emulator meeting `minSdk = 30` (the target/compile SDK is 37).

Set `sdk.dir` through Android Studio's generated `local.properties`; do not
commit machine-specific SDK paths. Dependency versions are in
`gradle/libs.versions.toml`.

## Serialized additions (#36)

The production writers are new-vault setup (`VaultSessionStorage.create`, using
create-only `saveNew`) and additions (`VaultSession.submitAdditions` through
`VaultSessionStorage.commit`). The manual wizard builds a one-entry list and
uses the same operation as an internal multi-entry proposal. No receiver or
batch UI is enabled. Raw `Vault.save` is a low-level filesystem API, not a second
production UI mutation route.

`AccountAdditionCandidate` privately copies the expected baseline and incoming
immutable entries, validates the entire resulting proposal and exposes only
fresh writable copies. Under the existing process-wide session I/O mutex,
commit authenticates current storage and rejects a different baseline unchanged.
It then writes once and uses B4 commit/verification results. Existing entries,
including duplicates, null names, Unicode and TOTP parameters, remain unchanged.
The wizard retains its name-collision checks; the generic boundary does not
choose a transfer collision/consent policy (#32/#33). Future callers must resolve
that policy before submitting the approved additions.

All session reads, setup, cleanup and commits use the same process-local mutex,
including recreated Activity owners. There is no evidence of another production
writer process, so no Desktop-style multi-process lock is added. Debug child
processes use separate fixture files. Future background/services/process writers
must revisit this assumption rather than call raw save concurrently.

Queued cancelled/invalidated mutations do not start. Already-started writes finish
the existing atomic protocol; Lock/cancellation invalidates UI publication, not
the commit. A batch is one candidate/file replacement, never one write per item.
No receipts, stable account IDs, vault migration or new KDF parameters are added.
See [batch regression checks](testing/batch-regressions.md).

## Build commands

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. JVM test reports
are in `app/build/reports/tests/testDebugUnitTest/`. Unit tests cover
vault save/load, invalid passwords, unsupported payload versions, password
strength, and wizard normalization/validation, navigation, and save guards. They do not prove full TOTP, Keystore, UI, or
cross-platform compatibility.

Storage/cache regression tests additionally cover unknown root/entry fields,
inspection errors, competing create-only publication, nullable/duplicate entries,
tampering/truncation and unsuccessful cache operations. See
[vault/cache regression checks](testing/vault-cache-regressions.md).

Account-validation regressions additionally exercise strict ASCII Base32,
supported TOTP parameter boundaries, invalid load/save data preservation and
unchanged historical key text/nullable names/duplicates. A hash-pinned synthetic
Python-written fixture is described in `app/src/test/resources/README.md`.
Formatted-key decoding has a fixed-time SHA1 check. The complete TOTP vector suite
now covers all 18 RFC 6238 eight-digit results, their 6/7-digit suffixes and 162
independent Python-generated boundary/parameter cases. Controlled countdown tests
cover period rollover without sleeps; unsupported digits including 10 are rejected.
Fixture provenance is in `app/src/test/resources/totp/README.md`. Full vault
interoperability uses the pinned #28/#29 runner below; JVM vectors do not prove device lifecycle.

Bounded-read regressions cover the real 10 MiB limit, overflow sentinels, small
chunks, read failures and guaranteed stream closure. An internal opener seam
lets a test grow a synthetic file after the metadata check, verifying that
`Vault.load` still consumes no more than the limit plus one byte. Existing
encrypted round-trip, wrong-password, tamper and truncation tests remain active.

Startup routing has injected inspection tests for every non-setup outcome and
cancellation propagation. It never reads credentials or authenticates a vault. The Android suite also exercises the
resolver with disposable encrypted files and the production storage-error/retry
component in an isolated Compose host. This does not launch MainActivity against
user storage or establish full lifecycle/session behavior; see
[startup regression checks](testing/startup-regressions.md).

Replacement fault tests inject creation, partial write, flush, sync, close,
partial backup, backup sync, move and cleanup failures. They verify unchanged
old bytes before publication, committed bytes after it, and retained ambiguous
candidates. Non-atomic fallback and automatic restore were removed; unsupported
atomic moves are tested as safe failures. The Android test exercises an actual
encrypted update on disposable storage. See
[replacement regression checks](testing/replacement-regressions.md).

Restart recovery inventory tests include main/backup/fixed/legacy temp states,
partial data, password/tamper/read errors and setup refusal. Recovery is read-only:
it does not repair files or resolve ambiguous candidates by deletion. Android tests
exercise isolated selection controls and a non-exported debug-only child process
that dies at synthetic filesystem stages, then restarts to inspect them. It never
opens MainActivity/user storage; only stage/directory metadata is passed in Intents.
See [restart recovery checks](testing/recovery-regressions.md) for exact limits.

The update API `Vault.save` returns `VaultSaveOutcome`; callers must inspect it,
not assume that return means a clean commit. `saveNew` remains create-only and
exception-based. All production update callers use `persistAccountCandidate`,
which separates commit knowledge from matching post-save verification; it never
automatically retries writes. An internal filesystem overload supports outcome
fault tests. No stored data/KDF migration is needed. Validation/preparation can
still throw before publication; unexpected writer exceptions are conservatively
uncertain. Cancellation propagates without assuming a committed write rolled back.
Session/cancellation ownership is implemented in `VaultSession` (#25). See
[save-outcome checks](testing/save-outcome-regressions.md).

The #21 review follow-up tests normalized path aliases, replaced files and changes
during authentication, plus actual Android symlinks and the debug host's directory
containment/error handling. The no-follow opener still uses bounded reads. File
fingerprints are temporary metadata checks, not durable account IDs or protection
against a compromised app UID/OS. See
[recovery path checks](testing/recovery-path-regressions.md).

The Android suite uses isolated synthetic preference names and temporary files:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --stacktrace
.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace
```

It checks real encrypted preference cleanup and create-only publication on Android.
The code-display Compose test also checks an unavailable state alongside a valid
synthetic code, with only the valid entry showing a countdown. Espresso is pinned
to 3.7.0 in the Android-test configuration: the older transitive version used
reflection against an InputManager method unavailable on API 37. Production
dependencies are unaffected. See [TOTP error checks](testing/totp-error-regressions.md).
It does not delete the default Keystore key or inspect the user's vault. A full
key-invalidation and lifecycle/IME pass remains separate from those tests.

GitHub Actions runs unit tests and builds the debug APK on `main`, `feature/*`,
and pull requests to `main`. Its setup step selects Java 17; the repository's
daemon criteria separately request Java 25 through toolchain provisioning.

## Actual pinned Desktop interoperability (#29)

Use the source checkout and Python 3.13.11 environment described in the
[corpus README](../app/src/test/resources/interop/README.md), then run:

```powershell
.\build\interop-venv\Scripts\python.exe tools/interop/run.py --desktop-source build/desktop-source
```

The runner verifies all source/dependency pins and fixture hashes, enables the
actual Python bridge and invokes the repository Wrapper's full
`:app:testDebugUnitTest :app:assembleDebug --stacktrace` tasks. CI uses Ubuntu
24.04 (a supported image for Python 3.13.11), installs the same pinned
Python/dependencies and checks out the exact Desktop commit; it does
not need a live Desktop application. Wrapper download timeout is 60 seconds
(formerly 10) after observed external CI timeouts; distribution URL/checksum and
cryptographic settings are unchanged. This does not guarantee remote service availability.

Plain Wrapper JVM tests still verify corpus hashes, Kotlin fields/fixed-time
codes and negative-case preservation. The single actual-Python method is skipped
when the explicit runner environment is absent. The runner reads JUnit XML and
fails if that method was missing/skipped/failed. Gradle test inputs include bridge
mode/source/Python paths and its scripts/pins, preventing reuse of a JVM-only
result when enabling/changing the bridge. Python/provider validation errors are
reported by type only; code/key/password values are omitted from assertions/logs.

Both round-trip directions operate only in disposable synthetic directories;
the corpus is immutable. This is file/semantic compatibility evidence, not an
account-package implementation, a physical-watch check or an independent audit.
See [execution evidence](testing/interop-regressions.md).

## Install and launch

For current session/Keystore integration and the still-required isolated
physical-watch phase, use the [C5 report/checklist](testing/wear-integration-2026-11-09.md).
Do not run a blanket connected-device suite against a personal Watch/profile.

```bash
adb devices -l
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-serial> shell am start -n org.desktop2fa.desktop.watch/.presentation.MainActivity
```

Choose the serial explicitly when an emulator and watch are both connected.
`-r` keeps existing app data when the app ID and signing key match. Do not
uninstall or clear app storage to solve an installation problem without first
considering the vault: the MVP has no user-facing backup/export.

## Physical watch over Wi-Fi

Enable developer options, ADB debugging, and wireless debugging on the watch.
Use the same Wi-Fi network as the computer and open **Pair new device**:

```bash
adb pair <watch-ip>:<pairing-port>
adb connect <watch-ip>:<connection-port>
adb devices -l
```

Enter the pairing code at the prompt. The connection port shown on the main
wireless-debugging screen can differ from the pairing port. Android Studio's
pairing interface is also an option. See the
[Wear OS instructions](https://developer.android.com/training/wearables/get-started/debug-wifi)
and [Samsung guide](https://developer.samsung.com/health/sensor/guide/connect-watch.html).
No root or bootloader unlock is needed. Disable debugging after testing.

## Manual UI checklist

Cache/Keystore loss and immediate Lock checks for #23 are described in the
[focused regression report](testing/cache-outcome-regressions.md). The debug
UI host uses only validated synthetic cache directories and prefixed preferences;
key-loss tests delete only their own randomly named Keystore alias.

The [accepted session/cache decision (#24)](session-policy.md) includes the
synthetic state/event table and future implementation/device gates. It removes
cache-based unlock and specifies a 60-second inactivity policy, with fullscreen
IME transitions distinguished from true departure. Password-only sessions and
explicit Lock are implemented in #25; automatic triggers remain #26. Current
[session regressions](testing/session-regressions.md) cover controlled late I/O,
recreation and a dedicated process-death host; they do not establish native IME
or physical-watch lifecycle behavior.

Recorded results: [emulator](testing/wear-os-emulator.md) and
[Galaxy Watch Ultra](testing/galaxy-watch-ultra.md). See the
[report index](testing/README.md) for scope and limitations.

Use a disposable test device/profile and synthetic secrets. Preserve any
existing vault and cached credentials before injecting fixtures; do not use
personal accounts in screenshots or logs.

- Create a vault; test weak-password feedback and absence of Remember.
- Unlock with correct/incorrect passwords; verify legacy cache never auto-opens
  a session, including after failed cleanup, recreation and restart.
- Save a synthetic account and verify persistence after relaunch.
- Scroll the list and reach the header, both footer actions, and feedback.
- Load fixtures with duplicate names, missing names, and identical secrets.
- Observe countdown changes and a code rollover; compare fixed-time vectors
  separately when testing the generator.
- Check the empty-vault state, open the account form, and lock the vault.
- Check keyboard/rotary input, long labels, font scaling, and different displays.
- Review app crash logs and measure startup/unlock/save costs on real hardware.

The 0.2.0 UI regression pass used the Large Round Wear OS emulator at
454 × 454. It covered list duplicates/unnamed entries, live code/countdown
changes, scrolling, empty state, opening the add-account form, and lock.
That MVP pass did not validate full account creation through the UI or physical
hardware.

For the subsequent account wizard, the Large Round emulator pass additionally
covered progression through all fields, duplicate-name and invalid-key feedback,
Back with draft retention, Show/Hide, normalization, saving, new-card confirmation,
and cancellation/reset. The saved synthetic vault was decrypted independently
to verify a single entry and the unchanged SHA1/6-digit/30-second defaults.
The 16 unit tests include 11 wizard tests. A subsequent
[Galaxy Watch Ultra pass](testing/galaxy-watch-ultra.md) covered the physical
Samsung keyboard and the wizard, including native Next/Done actions. Its
fullscreen editor still hides the wizard heading. The emulator IME intercepted
ADB text injection, so it was
temporarily disabled for scripted entry and restored afterwards; those tests
do not establish that typing is comfortable on a real watch.

## Screenshot provenance

Current app windows request capture protection; README images below predate
that control. See the [screen-exposure assessment](testing/screen-exposure-2026-11-09.md)
for the synthetic calibration test, restricted capture workflow and untested
physical/ambient cases. Do not disable the flag on personal data for a screenshot.

`assets/screenshots/accounts.png`, `vault-actions.png`, and `empty-vault.png`
were copied without editing from that emulator run. Names and codes belong to
synthetic test fixtures. The repeated account names demonstrate the regression
case; they are not production data. Screenshots were captured before the
metadata-only version bump; the displayed UI is unchanged.

`account-wizard.png` and `setup-key-validation.png` show the subsequent wizard
implementation on the same emulator. The invalid key is synthetic; its masked
field and explanatory error demonstrate submit-time validation. Scripted entry
used the temporary IME workaround described above.

## Versioning

The opt-in [physical KDF protocol](testing/kdf-measurement-protocol.md) provides
synthetic measurement commands and limits for the final device phase (#30).
Do not treat an emulator harness check as a physical latency/memory result.

The MVP increments app SemVer from 0.1.0 to **0.2.0** and Android `versionCode`
from 2 to **3** in `app/build.gradle.kts`. Feature additions justify the minor
increment while the project is pre-1.0. This is not a declaration of a stable
1.0 API or a published store release. The v0.2.0 tag marks the MVP baseline; subsequent wizard work is documented
under Unreleased in the changelog. Vault header/payload versions remain **1**.
