package org.desktop2fa.desktop.watch.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    /**
     * Derives a 32-byte key from password and salt using Argon2id.
     * Argon2id parameters matching desktop-2fa:
     * - time_cost: 4
     * - memory_cost: 131072 KiB (128 MiB)
     * - parallelism: 2
     * - hash_len: 32 bytes
     */
    @Synchronized
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(4)
            .withMemoryAsKB(128 * 1024)
            .withParallelism(2)
            .withSalt(salt)

        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val result = ByteArray(32)
        generator.generateBytes(passwordBytes, result, 0, result.size)
        return result
    }

    /**
     * Encrypts data using AES-256-GCM.
     * Returns 12-byte nonce + ciphertext + 16-byte authentication tag.
     */
    fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val nonce = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, nonce)
        val secretKeySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, gcmSpec)
        val ciphertext = cipher.doFinal(data)
        return nonce + ciphertext
    }

    /**
     * Decrypts data using AES-256-GCM.
     * Blob format: 12-byte nonce + ciphertext + 16-byte authentication tag.
     */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < 12) {
            throw IllegalArgumentException("Encrypted blob is too short")
        }
        val nonce = blob.copyOfRange(0, 12)
        val ciphertext = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, nonce)
        val secretKeySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, gcmSpec)
        return cipher.doFinal(ciphertext)
    }

    fun generateSalt(length: Int = 16): ByteArray {
        return ByteArray(length).apply { SecureRandom().nextBytes(this) }
    }
}
