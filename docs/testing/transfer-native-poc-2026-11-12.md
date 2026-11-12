# Transfer native PoC — acceptance ledger, 2026-11-12

This report covers the isolated [native experiment](../../native/README.md),
not real-secret reception or the Watch v2 migration. Follow the unchanged
[v0.3 acceptance criteria](../transfer/desktop-2fa-transfer-v0.3.md#12-proof-of-concept-and-test-plan--implementation-agent-handoff).
Every aggregate gate below remains open until its complete scope has evidence.

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
| T0 sources/APIs/fixes/toolchains | NOT RUN (incomplete) | Exact HEAD/API presence confirmed; security-fix review, Android build versions and all-platform toolchains remain open |
| T1 upstream tests/sanitizers | NOT RUN | Workflow prepared; record actual upstream/ASan/UBSan result after execution |
| T2 1000 Python/CFFI ↔ Kotlin/JNI exchanges | NOT RUN | Private-pipe harness prepared; requires executed workflow and real Wear OS counterpart, not only host JVM |
| T3 byte encodings/roles/leading zeroes | NOT RUN | Native C++ leading-zero/role cases passed; complete host/JNI/encoding mismatch matrix remains open |
| T4 wrong code/point/importer/IDs/PSK | NOT RUN | Native C++ wrong-code/wid cases passed; complete point/importer/PSK mutation matrix remains open |
| T5 no fallback/TLS1.2/ALPN/0-RTT/psk_ke | NOT RUN | Configuration and post-handshake checks implemented; adversarial peer tests remain open |
| T6 MITM/replay/mixed sessions | NOT RUN | No network/MITM experiment yet |
| T7 reconnect/restart | NOT RUN | Native C++ full-handshake reconnect passed; app/desktop restart not implemented |
| T8 expiration/attempt/timeouts | NOT RUN | Application preflight/lifetime state machine not implemented |
| T9 malformed inputs/framing | NOT RUN | Native boundary sizes checked; v1 framing/preflight parser not implemented |
| T10 distributions/real Wear OS | NOT RUN | No shipped binaries, watch ABIs or cross-platform acceptance |
| T11 vault transaction integration | NOT RUN | Separate v2 foundations in PR #67; no receiver/receipt transaction integration |
| T12 packaging/logs/lifecycle | NOT RUN | Export/key-log checks prepared; Android lifecycle and release packaging remain open |

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
