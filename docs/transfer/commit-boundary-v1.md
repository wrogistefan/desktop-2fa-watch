# Local transfer approval and verified publication

Implementation: `transfer/TransferCommit.kt`. This is the Watch storage backend;
network/session and UI integration are separate. No receiver is enabled here.

The caller must own the existing process-wide session writer mutex and a
lifecycle-owned non-cancellable mutation, just like manual additions. It must
bind local approval to the exact reviewed candidate and allow only one receiver
owner. Never construct approval from a remote message or persist it in saved
state. Revalidation loads/authenticates the current main vault. Any changed
baseline returns Stale without writing; prepare a fresh REVIEW and renew consent.
Unsupported/unmigrated storage never triggers automatic migration or empty setup.

TransferSaveGate is a one-request in-memory atomic boundary. Cancellation before
begin prevents writing, including cancellation during expensive revalidation.
After begin, cancellation is TOO_LATE; the same existing Vault.save atomic-file
path completes accounts and receipt together. There is no second journal or
plaintext sidecar. A cancelled/stale/failed approval must not be reused.

The cancel method is a request signal, not itself a terminal protocol response.
The session owner waits for the storage result before emitting CANCELLED/RESULT.
If revalidation discovers an already retained matching receipt, that historical
commit takes precedence over cancelling a replay. A different digest is TX_CONFLICT.
The receipt lookup does not write or refresh receipt order.

After any writer outcome, including an exception or NOT_COMMITTED, authenticate
and reread the actual file. Saved for a new publication requires the complete
expected snapshot and exact retained receipt. Never send a receipt copied only
from the proposed in-memory vault as RESULT. Missing/uninspectable main, mismatched
accounts/receipt or failed reload produces Unconfirmed, preserving a known commit
outcome when available. It never retries a save automatically. Ordinary recovery
artifacts require storage review; a confirmed main may still establish a saved
receipt, but no writable active-vault handoff is returned. A known recovery warning
is retained even if later metadata inspection is clean.

Unconfirmed does not mean no accounts were saved. The future session maps it to
fixed STORAGE_ERROR/uncertain-result UI and resolves retained receipts through
STATUS after reliable unlock/review. Exceptions and file paths are not wire diagnostics.
A verified old receipt is an idempotent result, not another account import.

No KDF, encryption, v1/v2 schema or Desktop vault changes are made. Tests use
synthetic values only. Authenticated reload and JVM file tests do not demonstrate
power-loss durability: Android directory durability, lifecycle and paired-device
acceptance remain required at the end under the owner's amended execution order.
