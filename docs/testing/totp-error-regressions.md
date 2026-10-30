# TOTP Error Rendering Regression Checks

Date: 2026-10-30. Scope: roadmap #15, after shared validation in #14. All keys,
codes and account examples are public synthetic test data.

## Behavior and risk

The generator independently validates Base32, algorithm, digits, period and
non-negative Unix time, including direct calls outside the vault boundary.
Failure raises a controlled exception without original key/provider diagnostics.
It does not substitute SHA1, a 30-second period or a dash-shaped code.

The reused code-display component renders an unavailable message with no code or
countdown. A failing entry does not stop adjacent valid entries. Valid countdowns
use the supplied account period. Defaults for newly created accounts and the
encrypted v1 format/KDF remain unchanged. This reduces misleading-code risk;
it is not an audit or a complete TOTP/interoperability assessment.

## Automated checks

JVM tests cover invalid direct generator calls, controlled error contents,
SHA1/SHA256/SHA512 at fixed RFC time, the mixed valid/invalid display-state mapping
and a non-default 45-second countdown. These complement the existing validation,
storage and wizard regressions. Full RFC vectors/boundaries are planned in #16.

The Compose instrumentation test uses a separate host rather than MainActivity:
no user vault is opened. It renders invalid and valid synthetic states together,
asserts the error and valid code are displayed, and checks that only one countdown
exists and no dash placeholder appears. Existing cache/storage device tests use
their isolated synthetic preference namespaces.

The first Android run stopped in Espresso initialization with
`NoSuchMethodException: InputManager.getInstance`; all three storage/cache tests
passed. Gradle dependency inspection identified transitive Espresso 3.5.0.
The test-only dependency is now pinned to stable 3.7.0, whose
[official release notes](https://developer.android.com/jetpack/androidx/releases/test)
describe replacing that reflection with getSystemService. No production-library
or SDK upgrade was required.

## Commands and final results

| Command | Final result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --stacktrace` | Passed: 45 JVM tests, no failures/errors, APK built |
| `.\gradlew.bat :app:assembleDebugAndroidTest --stacktrace` | Passed initially; final test APK rebuilt by connected run |
| `.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | Passed: 4 tests, including the Compose display test |
| `git diff --check` | Passed |

The final connected run used Espresso 3.7.0, `ANDROID_SERIAL=emulator-5580`,
Wear_OS_Large_Round / Android 17 / API 37. The JVM/APK checks were repeated after
the test dependency update. The emulator was started with `-read-only -no-snapshot
-no-window -no-audio`; existing AVD data is not saved by this test session.

## Limits

The emulator is Wear_OS_Large_Round, Android 17 / API 37, running read-only without
snapshot saving. The UI check exercises the reused display component, not the
complete MainActivity/lifecycle/IME flow, accessibility across font sizes, ambient
mode or a physical watch. Cryptographic provider failure is handled by a generic
controlled exception; the device run is not a hardware-provider fault-injection
or key-invalidation test. No networking or release is included.
