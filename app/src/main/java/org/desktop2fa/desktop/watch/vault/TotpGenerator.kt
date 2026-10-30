package org.desktop2fa.desktop.watch.vault

import org.bouncycastle.util.encoders.Base32
import org.desktop2fa.desktop.watch.model.SetupKey
import org.desktop2fa.desktop.watch.model.AccountValidation
import org.desktop2fa.desktop.watch.model.TotpEntry
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/** Controlled failure with no key material or cryptographic-provider diagnostics. */
class TotpGenerationException : Exception("TOTP code is unavailable")

object TotpGenerator {

    fun generateTotp(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1"
    ): String {
        return try {
            AccountValidation.requireValid(TotpEntry(secret = secret, period = period, digits = digits, algorithm = algorithm))
            require(timeSeconds >= 0) { "Unsupported TOTP time" }
            val decodedKey = Base32.decode(SetupKey.canonical(secret))
            val counter = timeSeconds / period
            val data = ByteArray(8)
            var value = counter
            for (i in 7 downTo 0) {
                data[i] = (value and 0xffL).toByte()
                value = value ushr 8
            }

            val hmacAlg = when (algorithm) {
                "SHA1" -> "HmacSHA1"
                "SHA256" -> "HmacSHA256"
                "SHA512" -> "HmacSHA512"
                else -> throw IllegalArgumentException("Unsupported TOTP algorithm")
            }

            val mac = Mac.getInstance(hmacAlg)
            mac.init(SecretKeySpec(decodedKey, hmacAlg))
            val hash = mac.doFinal(data)

            val offset = (hash[hash.size - 1].toInt() and 0xf)
            val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                    ((hash[offset + 1].toInt() and 0xff) shl 16) or
                    ((hash[offset + 2].toInt() and 0xff) shl 8) or
                    (hash[offset + 3].toInt() and 0xff)

            val otp = binary % 10.0.pow(digits.toDouble()).toInt()
            otp.toString().padStart(digits, '0')
        } catch (_: Exception) {
            throw TotpGenerationException()
        }
    }
}
