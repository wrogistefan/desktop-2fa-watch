# Wear OS Emulator UI Validation

Date: 2026-10-28. These functional checks cover the v0.2.0 list regression
fixes and the subsequent issue #11 account wizard. They are not a complete
instrumented UI suite or a physical-device usability assessment.

## Environment and build

| Item | Value |
|------|-------|
| AVD | Wear_OS_Large_Round |
| Display | 454 × 454 pixels, round |
| Build | Debug APK with the unreleased account wizard; app metadata 0.2.0 / code 3 |
| Input | ADB touch/key events; emulator Gboard temporarily disabled for scripted typing |
| Data | Synthetic encrypted vaults; existing vault/preferences preserved and restored |

The emulator's fullscreen IME intercepted injected text. It was temporarily
disabled to exercise app input, then restored. These results therefore do not
verify everyday software-keyboard behavior. The separate
[Galaxy Watch Ultra report](galaxy-watch-ultra.md) records checks with Samsung's
keyboard left enabled.

## Results

| Check | Result |
|-------|--------|
| Empty vault and opening the account wizard | Passed |
| One editable field per step with heading/progress | Passed |
| Service → account → setup key progression | Passed with injected Next/Done events |
| Back with draft retention | Passed: account value retained and key draft kept |
| Duplicate account name | Passed: rejected before advancing to the key step |
| Invalid Base32 character `0` | Passed: submit-time error displayed |
| Show/Hide | Passed with a synthetic setup key |
| Lowercase normalization | Passed: lowercase key converted to uppercase |
| Save and success navigation | Passed: new card and Account added confirmation visible |
| Cancel/reset | Passed: reopening starts empty; cancel does not append an entry |
| Legacy duplicate names, missing names, identical secrets | Passed with a four-entry fixture |
| List scrolling, header, and footer | Passed: both actions reachable |
| Countdown/code changes | Observed while the list was visible |
| Manual lock | Passed: returns to Unlock Vault |
| App crash log | No app crash found during the checks |

The saved synthetic vault was independently decrypted with Python primitives
to confirm a single entry, the normalized key, and SHA1/6-digit/30-second
defaults. This is not the complete desktop-library round trip planned in #10.
The original vault and credential preferences were restored and their hashes
matched the preserved copies.

## Unit tests and limits

The JVM suite has 16 tests: 11 wizard/state/Base32 tests, 3 vault tests, and
2 password-strength tests. Wizard tests cover valid padded/unpadded encodings,
formatting whitespace, incomplete lengths, incorrect padding, trailing bits,
confusable characters, validation timing, navigation, duplicate-name checks,
save claiming, and retained drafts on retry.

Failed-save retry and repeated-submit guards have unit coverage; filesystem
failures were not deliberately injected in the UI pass. Hardware keyboard
comfort, rotary input, font scaling, and comprehensive accessibility checks
were not established by this run. The later error-text color correction was
visually retested on the Ultra, not rerun as a complete emulator UI pass.

## Screenshots

These unedited captures use synthetic accounts. The account-list images show
the MVP regression cases; the wizard images show the subsequent development UI.

| List regression fixture | Reachable actions | Empty vault |
|:-----------------------:|:-----------------:|:-----------:|
| <img src="../../assets/screenshots/accounts.png" width="220" alt="Duplicate synthetic account names on the round emulator"> | <img src="../../assets/screenshots/vault-actions.png" width="220" alt="Add Account and Lock Vault actions"> | <img src="../../assets/screenshots/empty-vault.png" width="220" alt="Empty synthetic vault"> |

| Single-field wizard | Setup key validation |
|:-------------------:|:--------------------:|
| <img src="../../assets/screenshots/account-wizard.png" width="240" alt="Service-name step with a single input"> | <img src="../../assets/screenshots/setup-key-validation.png" width="240" alt="Submit-time feedback for an invalid Base32 zero character"> |
