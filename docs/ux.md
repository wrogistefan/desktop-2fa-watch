# Watch UX Specification

This describes the development screens after the 0.2.0 MVP. UI strings below match the
English labels currently shipped by the app.

## Screens and transitions

| Screen | Behavior | Next state |
|--------|----------|------------|
| Splash/loading | Displays `Loading Secure Vault...` during initialization or vault work | Setup, Unlock, Main, storage error, or the originating form after failure |
| Storage error | Inspection failed or only a recovery backup remains; Retry storage check, no Initialize action | Rechecked startup state |
| Create Vault | Password field, validation feedback, Remember on watch, Initialize Vault | Main after a successful save |
| Unlock Vault | Password field, Remember on watch, Unlock, error feedback | Main after a successful load |
| Main | Scrollable account cards, empty state, Add Account, Lock Vault | Add Account or Unlock |
| Add Account wizard | Service name → Account name → Setup key; one field per step, Back/Cancel, final Save Account | Main at the new card after save/reload; retains draft on error |

On startup, only confirmed absence of both vault and backup leads to setup.
Inspection errors block setup; create-only publication also refuses a competing
creator. If a vault exists, a cached password
is tried first; a missing or unusable cache leads to unlock. File access and
cryptographic work run on an I/O dispatcher. The generic loading message is
also used for creation and saving, not just startup.

## Main list

The list uses Wear OS `TransformingLazyColumn`. Header, each card, and the
footer have a single layout root. The footer contains both actions and any
success/error feedback; users may need to scroll to read it.

Cards display issuer, optional account name, TOTP code, and seconds remaining.
Missing issuers display `Service`. Missing names render as an empty line.
Time is refreshed approximately every second; no per-card copy action exists.
An invalid account or generation failure displays `Code unavailable` and
`Check account data and watch time` in place of its code/countdown. Other cards
continue rendering. No SHA1, 30-second period or dash-code fallback is applied.

The current list assigns positional keys with an `entry-` prefix so duplicate
names and secrets can coexist. Positions remain stable for the present append
workflow. Future delete/reorder/sync features should revisit stable entry IDs.

## Forms and feedback

The account wizard has three steps, each with a title above its field and a
progress indicator. The layout scrolls and applies IME insets. Keyboard
Next/Done validates and advances or saves; submission hides the keyboard and
clears focus so errors can be read. Errors use a polite accessibility live
region. Fullscreen editor behavior belongs to the installed IME and still
has been checked on Samsung; its fullscreen editor still hides the app heading.

Autofill is disabled on the activity view hierarchy. Wizard fields use ASCII, email, or password input as appropriate. Android
EditorInfo flags explicitly disable suggestions, autocorrection, completion,
and personalized learning, and request no fullscreen/extract UI. The step
heading is passed to the IME as its hint/field name. IMEs can ignore hints. The setup key is masked by default with an
explicit visibility toggle. Incomplete typing does not trigger errors;
submission validates Base32 encoding, including padding and trailing bits.

All fields remain required. The account step checks name uniqueness and the
final step rechecks it before saving. Back preserves the draft; Cancel discards
it. Hardware back follows the same step behavior. Saving claims the draft
synchronously, disables edits/navigation/submission, and shows Saving… on the
final action. Failed saves retain the draft with a generic error that does not
echo provider exceptions or secrets. A successful save resets the draft and
opens the list at the new card, which displays Account added.

Drafts remain in memory rather than saved instance state. Activity/process
recreation can discard unfinished input. Vault setup/unlock retain their
existing single-password forms and remember checkbox, without a confirmation
password field or password-visibility toggle there.

## Lock behavior

Lock transitions to Unlock immediately, clears active vault/form references,
and requests cache removal in the background. Unlock submission waits until the
attempt completes. Failed cache writes/deletions show a separate warning; cache
failure never retains the active vault. There is no inactivity lock or automatic
lock on backgrounding.

## Validation status

Manual testing on the Large Round emulator covered duplicate and unnamed
entries, identical secrets, scrolling, countdown/code changes, empty state,
opening the account form, and locking. Screenshots in the README use synthetic
data. This is not a full automated UI suite or an accessibility certification.

The wizard was also functionally checked on a physical Galaxy Watch Ultra.
See the [device report](testing/galaxy-watch-ultra.md) for results and screenshots.
Keyboard comfort still needs user feedback; rotary input, other screen sizes,
font scaling, and systematic Argon2 resource measurements remain outside that
pass. See the [development checklist](development.md#manual-ui-checklist).
