package org.desktop2fa.desktop.watch.vault

open class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

class InvalidPasswordException(message: String = "Invalid password or corrupted vault", cause: Throwable? = null) : VaultException(message, cause)

class CorruptedVaultException(message: String = "Vault contains invalid data", cause: Throwable? = null) : VaultException(message, cause)

class UnsupportedFormatException(message: String = "Unsupported vault format", cause: Throwable? = null) : VaultException(message, cause)

class VaultIOException(message: String = "Vault IO error", cause: Throwable? = null) : VaultException(message, cause)
