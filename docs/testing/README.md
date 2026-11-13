# UI Test Reports

The [migration backend report](vault-migration-backend-2026-11-12.md) records
copy/publication fault tests and actual Desktop v2 rejection/v1 recovery reading.

The [vault v2 codec report](vault-v2-regressions.md) records storage/receipt
regressions and distinguishes them from pending migration and transport gates.

The [physical SM-L705F report](physical-watch-2026-11-11.md) records current
synthetic device runs, initial fixture failures and remaining manual/measurement
checks. It does not close the physical roadmap gates.

The [KDF measurement protocol](kdf-measurement-protocol.md) prepares the opt-in
synthetic harness; actual physical measurements remain the final #30 gate.

The [dated Wear integration report/checklist](wear-integration-2026-11-09.md)
records the current #37 emulator pass and explicitly unperformed physical phase.
It does not close the hardware gate or turn older device reports into new evidence.

The [screen-exposure assessment](screen-exposure-2026-11-09.md) distinguishes
secure-window protection from task previews and unverified physical ambient behavior.

The [lookup report](lookup-regressions.md) records #35 exact-name precedence,
ambiguity-safe removal and reordered/null/duplicate JVM regressions.

The [batch report](batch-regressions.md) describes #36 immutable proposals,
serialized competing sessions and encrypted whole-batch save/failure checks.

The [pinned interoperability corpus](../../app/src/test/resources/interop/README.md)
records D1 #28 source/dependency provenance, synthetic fixtures and hash checks.
It does not claim a cross-language/device run; that execution is D2 #29.

The [interoperability execution report](interop-regressions.md) separately records
D2 actual pinned Python/Kotlin fields, codes, both directions and rejection checks.

The [session ownership report](session-regressions.md) records #25’s password-only
sessions, late I/O and synthetic Activity/process recreation checks. The
[lifecycle report](lifecycle-regressions.md) records #26 automatic locking and
emulator IME checks. Physical-watch integration remains #37.

The [cache outcome and immediate Lock report](cache-outcome-regressions.md)
records #23's isolated Keystore-loss and production-screen checks. It does not
replace the later integrated lifecycle/physical-watch checks in #37.

The reports record functional checks using synthetic vaults, including their
scope, input method, screenshots, and limitations. They complement the JVM unit
suite and do not replace an automated UI suite or a usability study.

| Environment | Report | Main limitation |
|-------------|--------|-----------------|
| Large Round Wear OS emulator, 454 × 454 | [Emulator UI validation](wear-os-emulator.md) | Software keyboard temporarily disabled for scripted entry |
| Galaxy Watch Ultra SM-L705F, 480 × 480 | [Physical-device UI validation](galaxy-watch-ultra.md) | Samsung's fullscreen editor hides the wizard heading |

Both environments covered account setup, validation, draft navigation,
save/cancel behavior, legacy duplicate entries, scrolling, and manual locking.
The Ultra pass additionally exercised the native Samsung Next/Done controls.

Storage/cache changes for #4/#5/#6 have a separate
[regression report](vault-cache-regressions.md). Its JVM and Android checks do not
replace the historical UI reports or constitute a new physical-watch UI pass.

The [TOTP error-rendering report](totp-error-regressions.md) records the separate
#15 generator and isolated Compose component checks and their device limitations.

The [startup regression report](startup-regressions.md) records #19's resolver,
create-only integration and isolated retry component checks. It is separate from
the historical full-app UI passes above.

The [replacement regression report](replacement-regressions.md) records #20's
injected I/O matrix and Android atomic-replacement checks, without claiming
physical power-loss durability or complete restart recovery.

The [restart recovery report](recovery-regressions.md) records #21's conservative
read-only decision table and separate-process tests. It does not claim automatic
promotion, writable repair, full-app lifecycle validation or power-loss safety.

The [save-outcome report](save-outcome-regressions.md) records #22's commit/
verification distinctions and storage-review feedback after post-save errors.

The [recovery path follow-up](recovery-path-regressions.md) records the assessment
of late #46 review findings, no-follow opens, changed selections and debug-host
containment tests, including the limits of metadata-based checks.

- [Watch migration-copy recovery, 2026-11-12](migration-recovery-2026-11-12.md): metadata inventory and explicit read-only opening; final run/device limitations are listed in the report.
- [Isolated Watch transport PoC, 2026-11-12](transfer-watch-poc-2026-11-12.md): synthetic-only standalone harness, executed JVM/Windows checks and pending device gates.

- [OFFER foundations, 2026-11-12](transfer-offer-foundations-2026-11-12.md): offline parser/planner and shared synthetic vectors; receipt/save integration remains pending.

- [Transfer publication backend, 2026-11-13](transfer-commit-2026-11-13.md): local approval gate, actual receipt verification and fault regressions; device tests deferred to final acceptance.

- [Transfer session integration (2026-11-13)](transfer-session-2026-11-13.md): synthetic ownership, consent and Lock/cancellation checks.

- [Transfer wire codec checks (2026-11-13)](transfer-wire-2026-11-13.md): strict schemas, exact bytes and bounded synthetic streams.

- [Transfer activation session checks (2026-11-13)](transfer-activation-2026-11-13.md): explicit consent, stale baselines and Lock during migration.
