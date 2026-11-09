package org.desktop2fa.desktop.watch.presentation

import org.desktop2fa.desktop.watch.model.AccountValidation
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.vault.Vault

/** Private copies: neither caller mutations nor a writer can change the captured proposal. */
internal class AccountAdditionCandidate(snapshot: VaultData, additions: List<TotpEntry>) {
    private val version = snapshot.version
    private val baseline = snapshot.entries.toList()
    private val proposed = additions.toList()

    init {
        require(version == 1) { "Unsupported candidate version" }
        require(proposed.isNotEmpty()) { "An addition requires accounts" }
        (baseline + proposed).forEach(AccountValidation::requireValid)
    }

    fun matches(current: VaultData): Boolean = current.version == version && current.entries == baseline
    fun vault(): Vault = Vault(VaultData(version, (baseline + proposed).toMutableList()))
}
