# Isolated Watch transport PoC — 2026-11-12

Developer smoke harness only; no real accounts, main-app network permission,
vault migration UI or receiver is enabled. The separate APK is
`org.desktop2fa.transfer.poc`. See [instructions and limits](../../native/poc/android/README.md).

## Executed checks

- `python tools/interop/run.py --desktop-source ../../app/build/desktop-pinned`
  with the existing SDK explicitly selected: **357 JVM tests, zero failures,
  errors or skips**, actual pinned Python bridge included, debug APK built.
  This checks the unchanged lexical scanner extracted for sharing with the PoC;
  it does not replace Android lifecycle/route tests.
- Standalone Wrapper `:app:compileReleaseKotlin :app:testDebugUnitTest`:
  **7 tests passed**, frame fragmentation/coalescing, length checks before
  payload reads, deadlines, malformed UTF-8/JSON, duplicate escaped keys,
  exact schemas/types, canonical Base64 and literal IPv4 endpoints.
- `python -m unittest discover -s native/poc -p test_smoke_server.py -v`,
  `D2FA_SMOKE_LIBRARY` pointing to the non-test Windows DLL from run
  [43291745986](https://github.com/wrogistefan/desktop-2fa-watch/actions/runs/43291745986):
  **4 tests passed, zero skips**, including real native loopback full TLS and
  wrong-code refusal. Loopback is harness evidence, not Watch Wi-Fi evidence.

## Failed setup checks and corrections

Initial root verification lacked an SDK location and stopped before tests;
setting ANDROID_HOME selected the existing SDK. Standalone Android initially
requested nonexistent `testReleaseUnitTest`; the default test variant is debug.
AGP rejected a Provider in the legacy assets source-set API, and additional
Kotlin sources initially used the Java rather than Kotlin source-set property.
Both were corrected before the seven passing JVM tests above.

CI first could not find sdkmanager in PATH, then could not find
`platforms;android-37`. The selected SDK's actual package is
`platforms;android-37.0` (API 37.0, platform revision 2), and the workflow now
uses the SDK manager's absolute path. Build tools remain 36.0.0, as in the
existing application build. These failed runs are not passing APK evidence.

## Pending evidence

Full three-ABI APK build/packaging CI is pending at this update. No connected
ADB device was available during the local check. No APK was installed on the
main application, no user vault was opened and no device data was cleared.
Physical Wi-Fi-only routing, runtime permission, screen-off/background and
network-loss cleanup, firewall, device ABI and 100-session median/p95 still
need execution. The terminal server's blocking getpass does not implement
automatic prompt expiry; Watch independently enforces its own limits.

T0–T12 aggregate gates remain open. The smoke path does not implement account
validation/import, REVIEW/RESULT, durable receipt commits, STATUS/CANCEL,
automatic reconnect or mDNS and cannot justify enabling real-secret reception.
