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
