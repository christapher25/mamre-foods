package com.mamre.billing.domain.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The PIN hash (Doc 2 s2, s6): salted PBKDF2 from javax.crypto, no library. Only the salt and this hash are ever stored; the
 * plain PIN is never stored, logged or exported. The stored value is "pbkdf2-sha256:<iterations>:<Base64 hash>", so the
 * iteration count travels with the value and can be raised later without a new setting.
 */
object PinHasher {
    const val ITERATIONS = 120_000
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PREFIX = "pbkdf2-sha256"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 6

    /** A PIN is 4 to 6 ASCII digits (Doc 2 s6). */
    fun isValidPin(pin: String): Boolean = pin.length in MIN_DIGITS..MAX_DIGITS && pin.all { it in '0'..'9' }

    fun newSalt(random: SecureRandom = SecureRandom()): String =
        Base64.getEncoder().encodeToString(ByteArray(SALT_BYTES).also { random.nextBytes(it) })

    fun hash(pin: String, saltBase64: String, iterations: Int = ITERATIONS): String =
        "$PREFIX:$iterations:" + Base64.getEncoder().encodeToString(derive(pin, saltBase64, iterations))

    /** Constant-time comparison of the derived key with the stored one; a malformed stored value never verifies. */
    fun verify(pin: String, saltBase64: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 3 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val actual = runCatching { derive(pin, saltBase64, iterations) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    /** True when the stored hash and salt are both present and readable; anything else is damaged data, not a wrong PIN. */
    fun isWellFormed(saltBase64: String?, stored: String?): Boolean {
        if (saltBase64.isNullOrEmpty() || stored.isNullOrEmpty()) return false
        val salt = runCatching { Base64.getDecoder().decode(saltBase64) }.getOrNull()
        if (salt == null || salt.isEmpty()) return false
        val parts = stored.split(":")
        if (parts.size != 3 || parts[0] != PREFIX) return false
        if (parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } == null) return false
        return runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull()?.isNotEmpty() == true
    }

    private fun derive(pin: String, saltBase64: String, iterations: Int): ByteArray {
        val salt = Base64.getDecoder().decode(saltBase64)
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
