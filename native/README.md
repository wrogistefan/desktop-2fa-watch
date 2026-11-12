# Isolated transfer profile experiment

This is developer-only proof-of-concept code for
[specification v0.3](../docs/transfer/desktop-2fa-transfer-v0.3.md), not a receiver
or a released transport. It is independent of the Android application and its
vault. Do not enter real accounts, vault passwords or secrets into this harness.
The [acceptance report](../docs/testing/transfer-native-poc-2026-11-12.md) records
actual results and missing evidence. Passing this harness is not an audit.

## Boundary and implementation

- Original BoringSSL at `594a44291086dcc7cef06cabec4c0b47afd674c0`, privately
  linked as static `ssl`/`crypto` into one wrapper. CMake refuses another HEAD.
  The build does not fetch a moving branch or use Android's private BoringSSL.
- `transfer/core.h` is the shared C ABI. Watch = Alice/TLS client, Desktop =
  Bob/TLS server. Names, I/C importer bytes, Alice/Bob message order, full 64-byte
  K, SHA-256 importer and P-256 use the supplied profile. No curve/KDF/HMAC/AEAD
  implementation is added. PAKE alone does not authorize application I/O.
- Each TLS object has one imported external PSK, TLS 1.3 only and exact ALPN;
  certificate/verify-mode configuration is absent. After Finished, version,
  ALPN, allowed cipher, P-256 ECDHE, no early data and no resumption are checked.
  Tickets and session caching are disabled. Reconnect creates fresh TLS objects
  using the retained native credential after prior full authentication.
- Memory BIOs separate TLS from sockets. Each feed/drain/read/write is bounded
  to 16384 bytes, with 65536-byte queue checks. Native handles are serialized,
  capped, checked and explicitly closed. JNI copies bounded arrays, validates
  lengths and clears its controlled buffers. No keys/inputs/exceptions are logged.
- `poc/interop.py` uses Python/CFFI for Bob and a separate Kotlin/JNI process for
  Alice. Private stdin/stdout binary RPC is a **test control channel**, not the
  transfer protocol. Test codes/full K never go through CLI arguments or logs.
  Only synthetic nonsecret markers are TLS application data. The test checks
  that no key log appears with SSLKEYLOGFILE set; the module installs no callback.

The C ABI does not implement START/CHALLENGE JSON, LAN discovery/Wi-Fi binding,
code generation/display/entry policy, three-attempt limits, monotonic deadlines,
approval, reconnect pacing, locking or receipt transactions. Those are mandatory
application-layer gates, not responsibilities this experiment has completed.
The host RPC harness adds no network service or Android permission; the separate Watch smoke APK described below adds networking only to its isolated package. No test RPC listener
may become an application feature.

## Repeatable developer build

Prerequisites: CMake >=3.22, Ninja, C/C++17 compiler, Git, pinned BoringSSL
checkout; JNI additionally needs a host JDK 17 and Python/CFFI needs the pinned
packages in `poc/requirements.txt`. Use the repository Gradle wrapper for the
isolated Kotlin peer. Record actual compiler/runtime versions with results.

On Linux (also available through WSL; paths below are placeholders):

```sh
git clone --no-checkout https://boringssl.googlesource.com/boringssl /path/to/boringssl
git -C /path/to/boringssl checkout --detach 594a44291086dcc7cef06cabec4c0b47afd674c0
sh native/transfer/run-core-tests.sh /path/to/boringssl /path/to/build
```

The helper scripts use explicit arguments to avoid PowerShell/WSL `-D` parsing
ambiguities. Keep build outputs under an ignored developer build directory.
They build a **test-only** library. The first local configuration attempt passed
the wrong source path across shells; that attempt is not build/test evidence.

For cross-language checks with a host JDK, use a fresh or explicitly configured
test build:

```sh
./gradlew -p native/poc/kotlin jar --stacktrace
python -m pip install -r native/poc/requirements.txt
cmake -S native/transfer -B build/interop -G Ninja \
  -DD2FA_BORINGSSL_SOURCE=/path/to/boringssl \
  -DD2FA_TEST_API=ON -DD2FA_JNI=ON -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build build/interop --target d2fa_core_test --parallel 2
ctest --test-dir build/interop --output-on-failure
python native/poc/interop.py --library build/interop/libd2fa_transfer.so \
  --jar native/poc/kotlin/build/libs/d2fa-native-interop-poc.jar
```

The native workflow additionally instruments the wrapper/BoringSSL with
ASan/UBSan, executes upstream `crypto_test`/`ssl_test`, and rebuilds without
`D2FA_TEST_API` before checking exported symbols. Linux results are recorded in the ledger; Windows/macOS host jobs are now
prepared but need their own executed results. Wear OS and release packaging
remain separate open gates.

## Test API and shipping restrictions

`D2FA_TEST_API=ON` retains/exports full K for in-memory comparison. **Never ship
that build or the PoC peer.** Default OFF removes the key-copy code and JNI
entry point. Only explicitly selected wrapper/JNI symbols are exported on Linux;
`poc/verify_exports.py` checks non-test configuration. That configuration is not
a release-ready binary: no platform acceptance or composition audit is implied.

The parent GPL license applies to this wrapper. BoringSSL retains its own
upstream licenses; future binary packaging must carry the required notices,
verify private symbols/ABIs, track fixes and repeat the profile gate after updates.
Newer upstream commits exist; their security impact has not yet been reviewed.
There is no guarantee that this baseline is the latest or vulnerability-free.
Controlled buffers are cleared where possible; Python/JVM/library/OS copies
cannot be guaranteed erased. No memory dump/report upload is configured.

## Desktop host matrix

The workflow additionally targets Windows Server 2022 (Visual Studio 2022,
NASM 2.16.03) and macOS 15 arm64 host runners. It checks 1000 JNI/CFFI exchanges and
non-test exports on each; a bounded pipe-reader thread avoids unsupported
Windows pipe selectors. macOS exports are restricted explicitly, and Windows
uses a private static CRT. These are test/build targets, not new minimum OS
support claims. Developer artifacts contain only the non-test core and licenses,
never test-key binaries, runtime data, frame logs or dumps. No published app
release, account receiver or end-user installation acceptance is implied.

A separate [Watch LAN smoke app](poc/android/README.md) exercises the manual IPv4 startup without vault/account access. It is a developer-only APK, with [explicit test evidence and remaining gates](../docs/testing/transfer-watch-poc-2026-11-12.md).
