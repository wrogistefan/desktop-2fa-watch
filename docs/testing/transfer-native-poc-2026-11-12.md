# Transfer native PoC — acceptance ledger, 2026-11-12

This report covers the isolated [native experiment](../../native/README.md),
not real-secret reception or the Watch v2 migration. Follow the unchanged
[v0.3 acceptance criteria](../transfer/desktop-2fa-transfer-v0.3.md#12-proof-of-concept-and-test-plan--implementation-agent-handoff).
Overall transfer acceptance remains open. PASS applies only to the explicitly labelled scope; host checks do not establish physical-device acceptance.

## Environment and completed preliminary checks

- BoringSSL HEAD `594a44291086dcc7cef06cabec4c0b47afd674c0`, source cloned from
  the official upstream. SPAKE2 and RFC 9258 importer declarations confirmed.
- Local native build: Ubuntu WSL, GCC 13.3.0, CMake 3.28.3, Ninja. This is Linux
  toolchain execution on a Windows host, not a Windows native compatibility test.
- Local Python is 3.12.3 in WSL; no CFFI module or Linux JDK was initially present.
  No system package installation or privileged Linux change was performed.
- The isolated Kotlin build uses JVM 17 bytecode and Kotlin 2.2.10. The native
  GitHub workflow provisions JDK 17 and Python 3.13.11/CFFI 2.0.0 separately.
- Initial direct PowerShell/WSL CMake invocation selected the wrong source;
  explicit shell helpers corrected argument handling. No success is claimed
  for that attempt.
- `sh native/transfer/run-core-tests.sh PINNED_SOURCE BUILD_DIRECTORY` passed
  1/1 CTest case (1.54 seconds total test execution): 1000 fresh native C++
  exchanges with full 64-byte K equality, TLS nonsecret marker delivery, a full
  reconnect, leading-zero code, wrong-code/role/wid rejection and pre-TLS
  application-I/O rejection. This does not execute JNI or CFFI. The final rerun
  after explicit SSL_connect/SSL_accept and handle-bound edits also passed 1/1
  (1.17 seconds total test execution), covering implementation `ef25c1b`.
- `.\gradlew.bat -p native/poc/kotlin jar --stacktrace` passed on Windows;
  it compiles the isolated peer, not a JNI runtime or Wear OS binary.

## T0–T12 status

| Gate | Status | Evidence / remaining work |
| --- | --- | --- |
| T0 sources/APIs/fixes/toolchains | NOT RUN (incomplete) | Exact pin/APIs and host/Android toolchains recorded; upstream security-fix assessment remains open |
| T1 upstream tests/sanitizers | PASS (Linux host) | Enabled upstream suites and wrapper ASan/UBSan passed; exact skips/toolchains below; no claim of Android sanitizer execution |
| T2 1000 Python/CFFI ↔ Kotlin/JNI exchanges | NOT RUN (complete device scope) | Host Linux/Windows/macOS 1000-exchange/full-K/TLS-marker checks passed; real Wear OS JNI counterpart not exercised |
| T3 byte encodings/roles/leading zeroes | NOT RUN (incomplete) | Host leading-zero/role/wid cases passed; full ASCII/UTF-8/NUL/device matrix remains open |
| T4 wrong code/point/importer/IDs/PSK | NOT RUN (incomplete) | Host wrong-code/wid rejection passed; full point/importer/PSK mutation matrix remains open |
| T5 no fallback/TLS1.2/ALPN/0-RTT/psk_ke | NOT RUN | Configuration/profile checks and pinned API behavior inspected; adversarial peer matrix remains open |
| T6 MITM/replay/mixed sessions | NOT RUN | No complete network/MITM experiment |
| T7 reconnect/restart | NOT RUN (incomplete) | Host full-handshake reconnect passed; application STATUS/restart flow remains open |
| T8 expiration/attempt/timeouts | NOT RUN (incomplete) | Synthetic preflight/whole-frame deadlines and hidden-input expiry tests passed; full session/review/device state coverage remains open |
| T9 malformed inputs/framing | NOT RUN (incomplete) | Strict JSON/Base64/framing and bounded native I/O regressions passed; full point/adversarial/device matrix remains open |
| T10 distributions/real Wear OS | NOT RUN (incomplete) | Three Watch ABIs and host developer binaries built/guarded; real-device, compiler-free installation and network/firewall acceptance remain open |
| T11 vault transaction integration | NOT RUN | Codec/migration/offline planning foundations exist; receiver approval/save/cancellation integration remains open |
| T12 packaging/logs/lifecycle | NOT RUN (incomplete) | Non-test export/license/package/key-log checks passed; physical lifecycle, backup and release acceptance remain open |

Record completed commands/results below before changing any status. Full-key
test values, codes, frames and PSKs must never be included in the evidence.
No physical watch model/API or 100-authentication median/p95 measurements exist
for this experiment. Do not infer them from emulator/WSL/CI execution.

## First CI execution and follow-up

Run [43268341814](https://github.com/wrogistefan/desktop-2fa-watch/actions/runs/43268341814),
implementation `a476ac4`:

- **PASS** Linux host Python 3.13.11/CFFI 2.0.0 ↔ Kotlin 2.2.10/JNI/JDK 17:
  1000 exchanges, matching full K, TLS nonsecret markers, reconnect, leading
  zeroes, wrong-code/wid rejection, and no file at the SSLKEYLOGFILE test path.
- **PASS** non-test rebuild export guard: no BoringSSL symbols or full-key test
  entry point in the wrapper's exported symbols.
- **PASS** wrapper CTest under ASan/UBSan (1000 exchanges; 1/1 CTest case).
- **FAIL** upstream test execution setup: crypto_test started 1892 tests but
  aborted when `./crypto/blake2/blake2b256_tests.txt` was unavailable from the
  repository root. This is not a passing upstream suite or a sanitizer finding.
  The workflow now runs upstream executables from the pinned source/test-data
  root. A complete rerun is required before marking that subset passed.

A later local wrapper rerun after input/output queue-bound checks passed 1/1
(1.54 seconds); it includes refusing input beyond 65536 bytes and output growth
beyond the configured queue. CI must verify this latest implementation too.
The T0–T12 aggregate gates above remain open: these Linux-host subsets do not
establish real Wear OS, supported desktop distribution or application readiness.

## Completed Linux host rerun

Run [43270087000](https://github.com/wrogistefan/desktop-2fa-watch/actions/runs/43270087000),
implementation `a803a11`, passed wrapper ASan/UBSan, Python/CFFI ↔ Kotlin/JNI
1000 exchanges and the non-test export/key-log checks. Toolchains recorded:
Ubuntu 24.04 runner, Clang 18.1.3, CMake 3.31.6, Go 1.24.13, Python 3.13.11,
CFFI 2.0.0, Kotlin 2.2.10 and Temurin JDK 17.0.21+9.

The upstream crypto suite reported **1891 passed, one skipped**, plus seven
upstream-disabled tests not executed. The skipped `AEADTest.WycheproofAESEAX`
contains upstream guards for unsupported EAX key/nonce sizes (192-bit keys and
nonce-size cases reported in the log). EAX is outside the selected TLS profile;
no upstream code or skip policy was changed. The SSL suite passed **483/483**.
These are executed enabled-suite results, not a claim that every upstream test
or every platform passed. The complete T0–T12 gates remain open as shown above.

Windows/macOS host jobs are now prepared separately, using the same BoringSSL
pin/profile. The test control pipe is portable across those hosts, exports are
restricted/checked per platform, and Windows uses a private static CRT. The
jobs have not yet passed at this report update. These runner targets do not
change the application's supported OS/API policy or establish Wear OS behavior.

## Desktop host gate follow-up

Run [43287476489](https://github.com/wrogistefan/desktop-2fa-watch/actions/runs/43287476489),
`59e1401`: Windows Server 2022 passed CTest, 1000 JNI/CFFI exchanges and
non-test exports. Linux jobs also passed. macOS 14 was cancelled before any
steps: runner annotation `macos-14-arm64-us-default is not supported`. This is
**not** macOS test evidence. The workflow now uses macOS 15 arm64, following
[runner retirement](https://github.com/actions/runner-images/issues/13518).
This is a CI host change, not a product minimum-OS decision.

Review follow-up makes macOS shell failure handling explicit and stages only
the current platform binary, retaining separately named project/BoringSSL
licenses. The `nm -gU` guard remains: uppercase `-U` excludes undefined symbols
in Apple's implementation; lowercase `-u` selects them. Windows `vswhere -find`
was exercised successfully by this run. Neither observation replaces the
pending macOS execution.

## Completed desktop host matrix

Run [43291727056](https://github.com/wrogistefan/desktop-2fa-watch/actions/runs/43291727056),
implementation `bbc2327`, passed Linux sanitizer/upstream suites, Linux interop,
Windows Server 2022 x64 and macOS 15 arm64 host CTest, 1000 Python/CFFI ↔
Kotlin/JNI exchanges on each host, and non-test export/key-log guards. In
particular Apple's actual `nm -gU` guard passed. PR #70 review discussions
record the source/toolchain evidence resolving that disputed review finding.
These are host runner checks, not firewall/manual-installation/real-Watch or
full T0–T12 acceptance. The earlier macOS-14 cancellation remains a failed
setup attempt, not compatibility evidence.
