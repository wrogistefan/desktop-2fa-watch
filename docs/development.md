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

## Build and unit tests

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. JVM test reports
are in `app/build/reports/tests/testDebugUnitTest/`. The 5 current tests cover
vault save/load, invalid passwords, unsupported payload versions, and two
password-strength examples. They do not prove full TOTP, Keystore, UI, or
cross-platform compatibility.

GitHub Actions runs unit tests and builds the debug APK on `main`, `feature/*`,
and pull requests to `main`. Its setup step selects Java 17; the repository's
daemon criteria separately request Java 25 through toolchain provisioning.

## Install and launch

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

Use a disposable test device/profile and synthetic secrets. Preserve any
existing vault and cached credentials before injecting fixtures; do not use
personal accounts in screenshots or logs.

- Create a vault; test weak-password feedback and remember on/off.
- Unlock with correct/incorrect passwords and relaunch with/without a cache.
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
It did not validate full account creation through the UI or physical hardware.

## Screenshot provenance

`assets/screenshots/accounts.png`, `vault-actions.png`, and `empty-vault.png`
were copied without editing from that emulator run. Names and codes belong to
synthetic test fixtures. The repeated account names demonstrate the regression
case; they are not production data. Screenshots were captured before the
metadata-only version bump; the displayed UI is unchanged.

## Versioning

The MVP increments app SemVer from 0.1.0 to **0.2.0** and Android `versionCode`
from 2 to **3** in `app/build.gradle.kts`. Feature additions justify the minor
increment while the project is pre-1.0. This is not a declaration of a stable
1.0 API or a published store release. The changelog entry stays unreleased
until publication. Vault header/payload versions remain **1**.
