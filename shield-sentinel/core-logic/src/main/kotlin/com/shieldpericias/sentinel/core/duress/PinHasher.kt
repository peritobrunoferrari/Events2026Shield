package com.shieldpericias.sentinel.core.duress

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Registro de PIN derivado — nunca o PIN em texto puro. */
data class PinRecord(
    val salt: ByteArray,
    val hash: ByteArray,
    val iterations: Int,
    val algorithm: String = PinHasher.ALGORITHM,
)

/**
 * Derivação PBKDF2-HMAC-SHA256 (disponível em Android e JVM). O número de
 * iterações é armazenado no registro para permitir migração futura.
 */
object PinHasher {
    const val ALGORITHM = "PBKDF2WithHmacSHA256"
    const val DEFAULT_ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val KEY_BITS = 256
    private val random = SecureRandom()

    fun create(pin: CharArray, iterations: Int = DEFAULT_ITERATIONS): PinRecord {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        return PinRecord(salt, derive(pin, salt, iterations), iterations)
    }

    fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Comparação em tempo constante. */
    fun matches(pin: CharArray, record: PinRecord): Boolean =
        MessageDigest.isEqual(derive(pin, record.salt, record.iterations), record.hash)
}
