package org.desktop2fa.desktop.watch.presentation

import org.bouncycastle.util.encoders.Base32
import java.util.Locale

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

/** Accept padded/unpadded RFC 4648 keys, store canonical padding for desktop readers. */
object SetupKey {
    fun normalize(value: String): String =
        value.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)

    fun error(value: String): String? {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return "Enter the setup key"
        val invalid = normalized.firstOrNull { it !in 'A'..'Z' && it !in '2'..'7' && it != '=' }
        // Do not echo user-supplied key material in error messages.
        if (invalid != null) return when (invalid) {
            '0' -> "0 is not a Base32 character; check for the letter O"
            '1' -> "1 is not a Base32 character; check the original key"
            else -> "Use only letters A–Z and numbers 2–7"
        }
        val body = normalized.trimEnd('=')
        if ('=' in body) return "Padding (=) is only allowed at the end"
        if (body.isEmpty() || body.length % 8 !in setOf(0, 2, 4, 5, 7)) {
            return "The key is incomplete; check its length"
        }
        val padded = body.padEnd(((body.length + 7) / 8) * 8, '=')
        if ('=' in normalized && normalized != padded) return "Incorrect Base32 padding"
        return try {
            val decoded = Base32.decode(padded)
            if (decoded.isEmpty() || Base32.toBase32String(decoded) != padded) {
                "The key has invalid trailing bits; check the last character"
            } else null
        } catch (_: Exception) {
            "The key is not valid Base32"
        }
    }

    fun canonical(value: String): String {
        require(error(value) == null) { "Invalid setup key" }
        val body = normalize(value).trimEnd('=')
        return body.padEnd(((body.length + 7) / 8) * 8, '=')
    }
}
