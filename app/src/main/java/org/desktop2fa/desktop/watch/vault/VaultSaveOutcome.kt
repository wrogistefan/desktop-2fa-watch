package org.desktop2fa.desktop.watch.vault

/** Update outcomes do not imply directory-sync/power-loss durability. */
enum class VaultSaveOutcome {
    NOT_COMMITTED,
    COMMITTED,
    COMMITTED_WITH_RECOVERY_ARTIFACTS,
    UNCERTAIN,
}
