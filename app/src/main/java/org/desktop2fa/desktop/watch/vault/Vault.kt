package org.desktop2fa.desktop.watch.vault

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.desktop2fa.desktop.watch.model.AccountValidation
import org.desktop2fa.desktop.watch.model.SetupKey
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.model.VaultData
import java.io.File
import java.io.IOException
import java.io.InputStream

class Vault(val data: VaultData = VaultData()) {

    val entries: List<TotpEntry>
        get() = data.entries.toList()

    @Synchronized
    fun addEntry(name: String, issuer: String, secret: String) {
        if (data.entries.any { it.accountName == name }) {
            throw IllegalArgumentException("An entry with name \"$name\" already exists. Names must be unique.")
        }
        val entry = TotpEntry(
            accountName = name,
            issuer = issuer,
            secret = SetupKey.canonical(secret),
        )
        AccountValidation.requireValid(entry)
        data.entries.add(entry)
    }

    fun getEntry(issuerOrName: String): TotpEntry {
        return data.entries.firstOrNull { it.issuer == issuerOrName || it.accountName == issuerOrName }
            ?: throw IllegalArgumentException("Entry '$issuerOrName' not found")
    }

    fun findEntries(name: String): List<TotpEntry> {
        return data.entries.filter { it.issuer == name || it.accountName == name }
    }

    @Synchronized
    fun removeEntry(issuerOrName: String) {
        val entry = getEntry(issuerOrName)
        data.entries.remove(entry)
    }

    @Synchronized
    fun save(file: File, password: String): VaultSaveOutcome = save(file, password, createOnly = false)

    @Synchronized
    internal fun save(file: File, password: String, io: VaultReplacementIO): VaultSaveOutcome =
        save(file, password, createOnly = false, io = io)

    /** Create and sync a new encrypted file without replacing an existing destination. */
    @Synchronized
    fun saveNew(file: File, password: String) {
        if (VaultFiles.inspect(file) != VaultPresence.MISSING) {
            throw VaultIOException("Vault already exists or requires recovery")
        }
        save(file, password, createOnly = true)
    }

    private fun save(
        file: File, password: String, createOnly: Boolean,
        io: VaultReplacementIO = VaultReplacementIO(),
    ): VaultSaveOutcome {
        validatePassword(password)
        // Validate the exact immutable-entry snapshot that will be serialized,
        // before any write, directory mutation or expensive KDF.
        val snapshot = data.copy(entries = data.entries.toMutableList())
        if (snapshot.version != 1) {
            throw UnsupportedFormatException("Unsupported vault data version: ${snapshot.version}")
        }
        snapshot.entries.forEach(AccountValidation::requireValid)

        try {
            val absFile = file.absoluteFile
            val parentDir = absFile.parentFile ?: File(".")
            parentDir.mkdirs()

            val header = VAULT_MAGIC + VAULT_VERSION
            val salt = CryptoUtils.generateSalt(16)
            val key = CryptoUtils.deriveKey(password, salt)

            val jsonString = json.encodeToString(VaultData.serializer(), snapshot)
            val rawJson = jsonString.toByteArray(Charsets.UTF_8)
            val encrypted = CryptoUtils.encrypt(key, rawJson)

            val totalBytes = header + salt + encrypted
            if (totalBytes.size > MAX_VAULT_SIZE) {
                throw VaultIOException("Vault content exceeds maximum supported size of $MAX_VAULT_SIZE bytes")
            }

            if (createOnly) {
                VaultFiles.writeNew(absFile, totalBytes)
                return VaultSaveOutcome.COMMITTED
            }

            return try {
                when (replaceVaultFile(absFile, totalBytes, io)) {
                    VaultReplacementResult.COMMITTED -> VaultSaveOutcome.COMMITTED
                    VaultReplacementResult.COMMITTED_WITH_RECOVERY_ARTIFACTS -> VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS
                }
            } catch (failure: VaultReplacementFailure) {
                if (failure.mayHaveCommitted) VaultSaveOutcome.UNCERTAIN else VaultSaveOutcome.NOT_COMMITTED
            }
        } catch (e: Exception) {
            if (e is VaultException) throw e
            throw VaultIOException("Failed to save vault: ${e.message}", e)
        }
    }

    companion object {
        val VAULT_MAGIC = byteArrayOf('D'.code.toByte(), '2'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte())
        val VAULT_VERSION = byteArrayOf(0x01)
        const val HEADER_LEN = 5
        const val MAX_VAULT_SIZE = 10 * 1024 * 1024

        private val json = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

        fun create(): Vault {
            return Vault(VaultData())
        }

        @Synchronized
        fun load(file: File, password: String): Vault = load(file, password, ::openVaultInput)

        internal fun load(file: File, password: String, openInput: (File) -> InputStream): Vault {
            validatePassword(password)

            val absFile = file.absoluteFile
            if (!absFile.exists()) {
                throw VaultIOException("Vault file not found")
            }

            if (absFile.length() > MAX_VAULT_SIZE) {
                throw UnsupportedFormatException("Vault file exceeds maximum supported size")
            }

            val blob: ByteArray
            try {
                blob = readVaultBytes(openInput(absFile))
            } catch (e: IOException) {
                throw VaultIOException("Failed to read vault file: ${e.message}", e)
            }

            if (blob.size < HEADER_LEN + 16 + 12 + 16) {
                throw UnsupportedFormatException("Vault file is too short or invalid format")
            }

            if (!blob.copyOfRange(0, 4).contentEquals(VAULT_MAGIC)) {
                throw UnsupportedFormatException("Invalid vault file format: incorrect magic header")
            }

            if (!blob.copyOfRange(4, 5).contentEquals(VAULT_VERSION)) {
                throw UnsupportedFormatException("Unsupported vault file version")
            }

            val salt = blob.copyOfRange(HEADER_LEN, HEADER_LEN + 16)
            val encrypted = blob.copyOfRange(HEADER_LEN + 16, blob.size)

            if (encrypted.isEmpty()) {
                throw UnsupportedFormatException("Vault file is invalid: empty encrypted blob")
            }

            val key = CryptoUtils.deriveKey(password, salt)

            val decryptedJsonBytes: ByteArray
            try {
                decryptedJsonBytes = CryptoUtils.decrypt(key, encrypted)
            } catch (ex: Exception) {
                throw InvalidPasswordException("Invalid password or corrupted vault", ex)
            }

            val rawJsonString = String(decryptedJsonBytes, Charsets.UTF_8)

            val vaultData: VaultData
            try {
                vaultData = json.decodeFromString(VaultData.serializer(), rawJsonString)
            } catch (e: SerializationException) {
                throw CorruptedVaultException("Vault contains invalid data", e)
            }

            if (vaultData.version != 1) {
                throw UnsupportedFormatException("Unsupported vault data version: ${vaultData.version}")
            }

            try {
                vaultData.entries.forEach(AccountValidation::requireValid)
            } catch (_: IllegalArgumentException) {
                throw CorruptedVaultException("Vault contains invalid account data")
            }

            return Vault(vaultData)
        }

        private fun validatePassword(password: String) {
            if (password.isEmpty()) {
                throw IllegalArgumentException("Password cannot be empty.")
            }
        }
    }
}
