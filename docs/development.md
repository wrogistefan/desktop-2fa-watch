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
are in `app/build/reports/tests/testDebugUnitTest/`. Unit tests cover
vault save/load, invalid passwords, unsupported payload versions, password
strength, and wizard normalization/validation, navigation, and save guards. They do not prove full TOTP, Keystore, UI, or
cross-platform compatibility.

Storage/cache regression tests additionally cover unknown root/entry fields,
inspection errors, competing create-only publication, nullable/duplicate entries,
tampering/truncation and unsuccessful cache operations. See
[vault/cache regression checks](testing/vault-cache-regressions.md).

The Android suite uses isolated synthetic preference names and temporary files:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --stacktrace
.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace
```

It checks real encrypted preference cleanup and create-only publication on Android.
It does not delete the default Keystore key or inspect the user's vault. A full
key-invalidation and lifecycle/IME pass remains separate from those tests.

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

Recorded results: [emulator](testing/wear-os-emulator.md) and
[Galaxy Watch Ultra](testing/galaxy-watch-ultra.md). See the
[report index](testing/README.md) for scope and limitations.

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

The MVP increments app SemVer from 0.1.0 to **0.2.0** and Android `versionCode`
from 2 to **3** in `app/build.gradle.kts`. Feature additions justify the minor
increment while the project is pre-1.0. This is not a declaration of a stable
1.0 API or a published store release. The v0.2.0 tag marks the MVP baseline; subsequent wizard work is documented
under Unreleased in the changelog. Vault header/payload versions remain **1**.
