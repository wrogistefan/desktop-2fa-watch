# Isolated Watch transport smoke app

Separate developer APK `org.desktop2fa.transfer.poc`, min API 30. It has its own
UID, no shared user ID, no vault/model/storage dependency and no account/OFFER
support. The main Watch app remains offline. Do not feed real accounts here.
Manual IPv4:port uses v0.3 `expected_sid=null`; discovery/resume are not implemented.

## Build

Use the repository Wrapper, SDK 37, NDK 28.2.13676358, CMake 3.22.1 and an
unmodified BoringSSL checkout at `594a44291086dcc7cef06cabec4c0b47afd674c0`.
CMake checks the commit. Native test API is OFF; the C++ runtime is static.

```powershell
.\gradlew.bat -p native/poc/android -Pd2faBoringSSLSource=C:/path/to/pinned-boringssl :app:testDebugUnitTest :app:assembleRelease --stacktrace
```

The release-named developer variant is signed with the debug key, not a product
release. CI checks the separate package ID, exact native libraries for
armeabi-v7a/arm64-v8a/x86_64, packaged license notices and absence of exported
BoringSSL/full-key test API. It retains the APK three days without installing it.

## Manual synthetic test

1. Install only this APK on a test Wear OS device. Do not uninstall, clear or
   replace `org.desktop2fa.desktop.watch`.
2. Build/download the non-test native Desktop library from the same sources.
   Install `native/poc/requirements.txt` in a developer virtualenv.
3. Run in an interactive terminal with the computer's actual LAN interface
   (replace the documentation-only IPv4 below):

```powershell
python native/poc/smoke_server.py --library C:/path/to/d2fa_transfer.dll --bind 192.0.2.10
```

4. Enter the printed IPv4:port on Watch, tap Start, grant local network access
   if requested. Enter the Watch code in the hidden computer prompt; retain
   leading zeros, with an optional single grouping space.
5. Both peers must report authenticated success after HELLO/READY/PING/PONG.
   PAKE key derivation alone is not success. A deliberately wrong code must fail.
6. Check background, screen-off, cancellation, permission denial, idle expiry
   and Wi-Fi loss. Record model/API, ABI, toolchains and redacted outcomes,
   excluding codes, frames, keys, device identifiers and network addresses.

The server binds one explicit IPv4 interface and an OS-selected port, accepts
one connection and exits. It neither changes the firewall nor advertises mDNS.
Codes are never read from CLI/environment. A bounded, hidden terminal reader
polls the monotonic deadline and pending socket on Windows/POSIX; no echo
fallback or blocking getpass is used. Expiry, peer closure or premature peer
data cancels input without waiting for Enter. POSIX terminal flags are restored
in finally; Windows uses no-echo character polling without changing console
mode. Input is bounded to nine ASCII characters including an optional grouping
space. Watch independently closes on its own deadlines. This remains partial
T8 evidence; it does not establish all product/session/device states.

## Protection and limits

Watch requests a Wi-Fi Network, rejects VPN/cellular/Bluetooth capabilities,
uses that Network's socket factory and releases its callback. No process-wide
network binding or default-network fallback exists. SDK 37 local network
permission precedes native context creation. See official
[Wear networking](https://developer.android.com/training/wearables/data/network-communication)
and [local network permission](https://developer.android.com/privacy-and-security/local-network-permission).
Actual route/firewall behavior needs physical verification.

A fresh random eight-digit code allows one PAKE execution, up to three attempts
per Activity opening. Only a nonsecret attempt counter survives recreation.
FLAG_SECURE, focus masking, no saved code/view state, screen-off and lifecycle
cleanup limit exposure. Idle input limit is 60 seconds, code lifetime three
minutes, measured by elapsed realtime. No wake lock is held. Native work runs
on IO and closes in finally; cancellation closes the socket. JVM copies cannot
be guaranteed erased. No secret persistence or Keystore dependency is added.

Complete-frame deadlines are not extended by fragments. Lengths are checked
before allocation. Strict UTF-8/JSON rejects BOM, duplicate/escaped-duplicate
keys, floats/exponents and depth above eight; schemas/types are exact. Only
the lexical scanner is shared with v2 storage; schemas stay separate and v1
parsing stays unchanged. Native memory-BIO writes fail closed rather than
retrying with a different transient buffer.

This is not complete T0–T12 evidence. Account approval, atomic receipt commits,
STATUS/CANCEL, automatic reconnect, mDNS, physical 100-session median/p95 and
product receiver integration remain open. Do not enable real-secret reception.
