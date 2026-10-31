# Recovery Path Review Follow-up (#21 / #46)

Scope: assess the late #46 review, harden file opening/selection and tighten the
debug crash-test host. This is a separate follow-up after #22, not a duplicate
issue or a claim that every review severity label establishes an exploit.
All paths, passwords, files and test fixtures are synthetic.

## Changes and limits

- Default vault reads open a `FileChannel` with READ/NOFOLLOW_LINKS, then use the
  existing bounded/closing input. The final path component cannot be followed as
  a symlink. Unsupported providers fail instead of falling back to weaker opening.
- Inventory lexically normalizes absolute paths and captures size, creation/
  modification times and the optional provider file key. Recovery checks that
  snapshot before and after loading; ordinary replacement/change needs a refresh.
  This metadata is ephemeral, not a persistent account/file identity scheme.
- The debug host refuses linked or non-child cache directories, reports only a
  constant ERROR marker for missing/broken fixtures, and intentionally kills its
  own process without relying on lifecycle cleanup. Controller waits remain
  bounded sleeping polls, with separate startup/shutdown deadlines; checked NIO
  deletion reports cleanup failures instead of ignoring them.
- Recovery buttons have explicit composition keys, Back defensively clears the
  source marker, and the manual unambiguously says password first, then tap a file.

`NOFOLLOW_LINKS` is an [open option](https://developer.android.com/reference/java/nio/file/LinkOption)
on supported Android providers. [File keys](https://developer.android.com/reference/java/nio/file/attribute/BasicFileAttributes#fileKey())
are optional/provider dependent and may be reused. Before/after metadata checks
are not descriptor-level immutable identity or a guarantee against a malicious
same-UID/root writer manipulating paths/metadata. Parent directories remain
trusted app-private storage. Android's [sandbox](https://source.android.com/docs/security/app-sandbox)
uses kernel-enforced UID/permission isolation; following a path alone does not
grant privileges to read another app's private data. No sandbox escape has been
demonstrated by this review/test work. This is defense in depth under the existing
trustworthy-OS model, not an audit or guarantee against a compromised OS.

## Assessment of the remaining review suggestions

| Finding | Assessment |
| --- | --- |
| Non-regular legacy item blocks inventory | Intentional safe error, caught by startup/recovery UI; skipping uncertain evidence must not permit setup. Existing invalid-inventory tests retain this behavior. |
| Generic recovery failure message | Deliberate constant feedback covers password/integrity/unsupported data without provider diagnostics; input remains editable and Back clears it. No claim of distinguishing wrong password from tampering. |
| Canonicalize/deduplicate all path identities | Lexical alias normalization added. Distinct hard-link names remain distinct recovery evidence, not silently merged. |
| Kill before onDestroy finishes | Required crash semantics; documented, with cleanup owned by the surviving test controller. |
| Shared process name | A colon-prefixed process is app-private; this single debug component has a documented dedicated process, not a cross-app shared process. |
| Increase/remove polling bounds | No unbounded wait added. Polls sleep 100 ms, and startup/shutdown get independent 30-second bounds. Missing-fixture ERROR now fails promptly. |

## Regressions

Three added JVM cases verify normalized aliases, a listed regular file replaced
before loading (zero decoder calls), and a change injected during authentication
(no stale view returned, no attempted rollback of the test's new bytes).

Android cases create real symlinks only inside disposable app-cache test paths:
the normal loader refuses one, and recovery refuses a symlink substituted after
inventory validation. Original synthetic encrypted bytes stay intact. A second
case verifies that the debug directory validator rejects a linked directory and
traversal without touching its synthetic target. The crash-test controller also
checks a missing fixture produces only ERROR and terminates the child process.

The full previous crypto, RFC, version, bounded-read, replacement, cache, outcome
and restart suites remain active. No actual user vault/cache is opened and no
real Keystore alias is removed. Instrumentation runs in the target app process
without opening MainActivity; only the dedicated debug child is intentionally killed.
Regular v1 files need no migration; linked paths/changed selections are refused
intact. No format/KDF, network, release or repository-setting change.

## Verification

Local run: 2026-10-31, read-only `Wear_OS_Large_Round` AVD, Android 17 / API 37,
explicitly selected as `emulator-5580`, with
`-read-only -no-snapshot -no-window -no-audio` (no saved AVD changes).

| Command / inspection | Result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest --stacktrace` | Passed: 306 tests, zero failures/errors |
| `.\gradlew.bat :app:assembleDebug --stacktrace` | Passed |
| `.\gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | Passed: 14 tests, zero failures/errors; test APK rebuilt |
| `.\gradlew.bat :app:processReleaseMainManifest --stacktrace` | Passed (up-to-date) |
| Merged release main manifest | No synthetic activity/process or INTERNET permission; no release APK built/published |
| `git diff --check` | Passed |

Emulator checks do not prove
physical-watch durability, all provider semantics, full lifecycle/IME behavior,
hardware-backed Keystore or secure memory erasure. No power-cut result is claimed.
