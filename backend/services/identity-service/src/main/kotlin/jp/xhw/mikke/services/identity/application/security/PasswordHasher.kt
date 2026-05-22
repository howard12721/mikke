package jp.xhw.mikke.services.identity.application.security

import jp.xhw.mikke.services.identity.model.PasswordHash
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PasswordHasher(
    private val iterations: Int = 120_000,
    private val keyLengthBits: Int = 256,
    private val saltLengthBytes: Int = 16,
) {
    private val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    private val secureRandom = SecureRandom()

    fun hash(password: String): PasswordHash {
        val salt = ByteArray(saltLengthBytes).also(secureRandom::nextBytes)
        val hash = derive(password, salt)
        return PasswordHash(
            iterations = iterations,
            hash = Base64.getEncoder().encodeToString(hash),
            salt = Base64.getEncoder().encodeToString(salt),
        )
    }

    fun verify(
        password: String,
        encoded: PasswordHash,
    ): Boolean {
        val storedIterations = encoded.iterations
        val salt = Base64.getDecoder().decode(encoded.salt)
        val expectedHash = Base64.getDecoder().decode(encoded.hash)
        val actualHash = derive(password, salt, storedIterations)

        return expectedHash.contentEquals(actualHash)
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        customIterations: Int = iterations,
    ): ByteArray {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, customIterations, keyLengthBits)
        return secretKeyFactory.generateSecret(spec).encoded
    }
}
