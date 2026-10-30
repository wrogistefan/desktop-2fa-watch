package org.desktop2fa.desktop.watch.presentation

import org.desktop2fa.desktop.watch.model.SetupKey

enum class AccountStep(val heading: String) {
    SERVICE("Service name"), ACCOUNT("Account name"), SECRET("Setup key")
}

/** In-memory only: never persist a draft secret in saved instance state. */
data class AccountWizardState(
    val step: AccountStep = AccountStep.SERVICE,
    val issuer: String = "",
    val account: String = "",
    val secret: String = "",
    val error: String? = null,
    val isSaving: Boolean = false
) {
    val value: String
        get() = when (step) {
            AccountStep.SERVICE -> issuer
            AccountStep.ACCOUNT -> account
            AccountStep.SECRET -> secret
        }

    fun edit(value: String): AccountWizardState {
        if (isSaving) return this
        return when (step) {
            AccountStep.SERVICE -> copy(issuer = value, error = null)
            AccountStep.ACCOUNT -> copy(account = value, error = null)
            AccountStep.SECRET -> copy(secret = SetupKey.normalize(value), error = null)
        }
    }

    fun back(): AccountWizardState = if (isSaving) this else copy(
        step = when (step) {
            AccountStep.SERVICE -> AccountStep.SERVICE
            AccountStep.ACCOUNT -> AccountStep.SERVICE
            AccountStep.SECRET -> AccountStep.ACCOUNT
        },
        error = null
    )

    /** A successful final submission claims the save before any coroutine starts. */
    fun submit(existingNames: List<String?>): AccountWizardState {
        if (isSaving) return this
        return when (step) {
            AccountStep.SERVICE -> if (issuer.isBlank()) copy(error = "Enter a service name")
                else copy(issuer = issuer.trim(), step = AccountStep.ACCOUNT, error = null)
            AccountStep.ACCOUNT -> when {
                account.isBlank() -> copy(error = "Enter an account name")
                account.trim() in existingNames -> copy(error = "This account name already exists")
                else -> copy(account = account.trim(), step = AccountStep.SECRET, error = null)
            }
            AccountStep.SECRET -> {
                val keyError = SetupKey.error(secret)
                when {
                    keyError != null -> copy(error = keyError)
                    account in existingNames -> copy(step = AccountStep.ACCOUNT,
                        error = "This account name already exists")
                    else -> copy(secret = SetupKey.canonical(secret), error = null, isSaving = true)
                }
            }
        }
    }
}
