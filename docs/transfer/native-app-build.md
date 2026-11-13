# Optional native core in the Watch app build

The ordinary app keeps its existing application ID and offline manifest. Its
native option defaults to false, needs no BoringSSL checkout/NDK, and packages
no transfer-native library or native-license assets. No receiver UI or native-loading
entry point is enabled by this build change. It does not migrate a vault.

An explicitly enabled developer build may package the exact shared native core:

```powershell
.\gradlew.bat -Pd2faTransferNative=true -Pd2faBoringSSLSource=C:/path/to/pinned-boringssl :app:assembleDebug --stacktrace
```

The supplied BoringSSL checkout must be at
`594a44291086dcc7cef06cabec4c0b47afd674c0`; CMake checks HEAD. A trusted, unmodified
source checkout/build machine is required. There is no dependency fetch at app
runtime or fallback to a system TLS/PAKE implementation. Use SDK 37, NDK
28.2.13676358 and CMake 3.22.1. Two compile jobs per ABI bound native build concurrency.
ABIs are armeabi-v7a, arm64-v8a and x86_64, with private static BoringSSL/C++.
D2FA_TEST_API is OFF and no full-key comparison method is present in the shared
Android binding. JNI class/method names have an explicit release keep rule.

The same Kotlin `org.desktop2fa.transfer.NativeCore` binding now lives under
`native/transfer/kotlin` and is compiled into both the product app and isolated
PoC. The object loads the library only when accessed; merely compiling the
binding does not load JNI. Future receive code must check
`BuildConfig.TRANSFER_NATIVE_ENABLED` before touching it, acquire the receive
owner/permissions, and close the native handle on every exit. No app code calls
it yet. The separate PoC remains a different UID/package with no vault access.

Enabled APKs package project and BoringSSL license notices. The packaging guard
checks exact application identity, three exact libraries, expected offline
permission state, private core exports and absence of test-key API. The existing
Compose `libandroidx.graphics.path.so` is allowed explicitly for the expected
ABIs; it is present in pre-transfer ordinary APKs too. CI builds option
off, on, and off again to catch stale native/notice artifacts. Enabled packaging
is not evidence of JNI runtime authentication or real Watch acceptance. The
native gate ledger and final physical/paired Desktop tests remain required;
this experimental path is not a release acceptance result.

This stage adds no INTERNET/local-network permission and no network operation,
changes no KDF/vault format and touches no Desktop application/repository.
