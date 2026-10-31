# UI Test Reports

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
