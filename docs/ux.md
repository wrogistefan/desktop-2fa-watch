# Watch UX Specification

This describes the implemented 0.2.0 screens. UI strings below match the
English labels currently shipped by the app.

## Screens and transitions

| Screen | Behavior | Next state |
|--------|----------|------------|
| Splash/loading | Displays `Loading Secure Vault...` during initialization or vault work | Setup, Unlock, Main, or the originating form after failure |
| Create Vault | Password field, validation feedback, Remember on watch, Initialize Vault | Main after a successful save |
| Unlock Vault | Password field, Remember on watch, Unlock, error feedback | Main after a successful load |
| Main | Scrollable account cards, empty state, Add Account, Lock Vault | Add Account or Unlock |
| Add TOTP Account | Issuer, Account Name, Secret (Base32), Save Account | Main after save/reload; remains on form after error |

On startup, a missing vault leads to setup. If a vault exists, a cached password
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

The current list assigns positional keys with an `entry-` prefix so duplicate
names and secrets can coexist. Positions remain stable for the present append
workflow. Future delete/reorder/sync features should revisit stable entry IDs.

## Forms and feedback

Forms scroll vertically; keyboard actions submit the create/unlock/save flows.
Password and secret fields mask their contents. All add-account fields are
required, account names must be unique for new entries, and errors remain on
the form. There is no confirmation password field or password-visibility toggle.

The app uses simple screen state rather than a navigation back stack. A
dedicated in-app cancel/back action is not implemented for the account form.

## Lock behavior

Lock transitions to Unlock immediately, clears active vault/form references,
and requests cache removal in the background. No success indication confirms
credential deletion. There is no inactivity lock or automatic lock on backgrounding.

## Validation status

Manual testing on the Large Round emulator covered duplicate and unnamed
entries, identical secrets, scrolling, countdown/code changes, empty state,
opening the account form, and locking. Screenshots in the README use synthetic
data. This is not a full automated UI suite or an accessibility certification.

Physical-watch checks remain pending, including keyboard comfort, rotary input,
small-screen clipping, font scaling, and Argon2 resource use. See the
[development checklist](development.md#manual-ui-checklist).
