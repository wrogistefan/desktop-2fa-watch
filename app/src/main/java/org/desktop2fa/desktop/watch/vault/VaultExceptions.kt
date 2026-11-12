package org.desktop2fa.desktop.watch.vault

open class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

class InvalidPasswordException(message: String = "Invalid password or corrupted vault", cause: Throwable? = null) : VaultException(message, cause)

class CorruptedVaultException(message: String = "Vault contains invalid data", cause: Throwable? = null) : VaultException(message, cause)

class UnsupportedFormatException(message: String = "Unsupported vault format", cause: Throwable? = null) : VaultException(message, cause)

open class VaultIOException(message: String = "Vault IO error", cause: Throwable? = null) : VaultException(message, cause)

/** Name selection must not choose an arbitrary historical duplicate. */
class AmbiguousEntryException : IllegalArgumentException("Multiple accounts match; select explicitly")

/** Fixed diagnostic categories; never chain a parser exception carrying decrypted input. */
internal enum class VaultPayloadPhase { UTF8, JSON_SYNTAX, JSON_TYPES, MODEL_SCHEMA, MODEL_VALIDATION }
internal class VaultPayloadDiagnosticException(val phase: VaultPayloadPhase) :
    IllegalArgumentException("Vault payload rejected at ${phase.name}")
