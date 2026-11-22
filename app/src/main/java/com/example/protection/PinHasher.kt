package com.example.protection

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 12_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    const val PIN_LENGTH = 6

    private val secureRandom = SecureRandom()

    fun isValidPinFormat(pin: String): Boolean {
        return pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
    }

    fun hashPin(pin: String): Pair<String, String> {
        require(isValidPinFormat(pin)) { "PIN must be exactly $PIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val hash = pbkdf2(pin.toCharArray(), salt)
        return Base64.getEncoder().encodeToString(salt) to
            Base64.getEncoder().encodeToString(hash)
    }

    fun verifyPin(pin: String, saltBase64: String, expectedHashBase64: String): Boolean {
        if (!isValidPinFormat(pin)) return false
        if (saltBase64.isBlank() || expectedHashBase64.isBlank()) return false
        return runCatching {
            val salt = Base64.getDecoder().decode(saltBase64)
            val expectedHash = Base64.getDecoder().decode(expectedHashBase64)
            val actualHash = pbkdf2(pin.toCharArray(), salt)
            MessageDigest.isEqual(actualHash, expectedHash)
        }
            .getOrDefault(false)
    }

    private fun pbkdf2(pinChars: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pinChars, salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
