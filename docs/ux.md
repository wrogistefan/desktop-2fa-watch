# Watch UX Specification

This describes the development screens after the 0.2.0 MVP. UI strings below match the
English labels currently shipped by the app.

## Screens and transitions

| Screen | Behavior | Next state |
|--------|----------|------------|
| Splash/loading | Displays `Loading Secure Vault...` during initialization or vault work | Setup, Unlock, Main, storage error, or the originating form after failure |
| Storage error | Inspection failed, recovery artifacts remain, or a save needs reconciliation; Retry storage check / Review recovery files, no Initialize action | Rechecked startup state or read-only recovery selection |
| Read-only recovery | Password and explicit current/backup/pending choice; no file promotion/deletion | Authenticated code view with Add Account disabled; Lock returns to storage check |
| Create Vault | Password field, validation feedback, Initialize Vault; no Remember | Main after a successful save |
| Unlock Vault | Password field, Unlock, cleanup/error feedback; no Remember | Main after a successful load |
| Main | Scrollable account cards, empty state, Add Account, Lock Vault | Add Account or Unlock |
| Add Account wizard | Service name → Account name → Setup key; one field per step, Back/Cancel, final Save Account | Main after clean commit/matching verification; otherwise clear submitted draft and review storage |

On startup, only confirmed absence of both vault and backup leads to setup.
Inspection errors block setup; create-only publication also refuses a competing
creator. An existing vault always leads to manual unlock; startup never reads
remembered credentials or opens Main. File access and
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
final action. Input validation preserves the editable draft before a storage
attempt. A non-clean storage outcome clears the submitted draft, detaches the
active vault and enters storage review, distinguishing not-committed/committed/
uncertain results without raw exception text. A post-commit read error must never
offer blind re-addition. A successful verified save resets the draft and
opens the list at the new card, which displays Account added.

Drafts remain in memory rather than saved instance state. Activity/process
recreation can discard unfinished input. Vault setup/unlock retain their
single-password forms without Remember, a confirmation password field or a
password-visibility toggle there. Submitted input is cleared; only the active
in-memory session retains the password needed for writes.

## Lock behavior

The password-only and explicit-Lock parts of the [session decision](session-policy.md)
are implemented by #25. Every launch/recreation needs manual unlock. Lock is also
available during loading and wizard saving; it hides codes and clears sensitive
state before pending I/O completes. Session generations reject late success and
error publication. New actions stay disabled until pending work and legacy-cache
cleanup finish. A save completing after Lock requires storage review and manual
unlock, without restoring the draft or silently retrying the addition.
A disposed owner's non-secret outcome is handed to the next Activity's storage
review and retained until explicit Retry. At 30 seconds a pending-work notice
explains recovery without pretending the writer stopped or enabling competing
actions. Recovery password edits and navigation are disabled during pending I/O;
success also clears trailing password edits.

Legacy cleanup warnings remain visible through manual unlock and distinguish
unconfirmed deletion from the locked session. Current code never reuses leftover
cache; older versions may after downgrade. Screen off/device lock, Activity stop
and 60 seconds without input now clear the session and drafts. Pause/focus loss
alone hides codes, preserving an unexpired draft. Native text edits count as
activity; merely opening an editor does not disable timeout. Resume uses current
wall time. Physical-watch/OEM IME integration still needs #37 evidence; see the
[current emulator report](testing/lifecycle-regressions.md).

## Validation status

All app screens request secure-window capture protection; API 33+ also disables
task screenshots. Screenshots, recording or remote visual support may show a
blank app. Normal input and accessibility semantics remain available. This does
not cover the keyboard's separate window or guarantee ambient redaction. See
the [assessment and pending device matrix](testing/screen-exposure-2026-11-09.md).

Manual testing on the Large Round emulator covered duplicate and unnamed
entries, identical secrets, scrolling, countdown/code changes, empty state,
opening the account form, and locking. Screenshots in the README use synthetic
data. This is not a full automated UI suite or an accessibility certification.

The wizard was also functionally checked on a physical Galaxy Watch Ultra.
See the [device report](testing/galaxy-watch-ultra.md) for results and screenshots.
Keyboard comfort still needs user feedback; rotary input, other screen sizes,
font scaling, and systematic Argon2 resource measurements remain outside that
pass. See the [development checklist](development.md#manual-ui-checklist).
