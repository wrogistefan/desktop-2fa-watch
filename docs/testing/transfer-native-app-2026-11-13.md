# Optional native app packaging — 2026-11-13

Local off/on/off APK verification and CI are pending. Use the repository Wrapper
and pinned source/toolchains described in
[the build contract](../transfer/native-app-build.md). The shared JNI Kotlin
binding is compiled by the product app and the standalone synthetic PoC.

APK checks inspect only generated artifacts: exact package identity, offline
permission state, absent transfer libraries/notices when off, three private libraries and
licenses when on, and no exported BoringSSL/full-key test API. No APK is installed
and no application/user vault is read. Device runtime, native authentication and
paired Desktop acceptance remain NOT RUN at this stage.
