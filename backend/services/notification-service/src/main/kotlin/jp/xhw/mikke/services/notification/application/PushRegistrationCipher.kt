package jp.xhw.mikke.services.notification.application

import jp.xhw.mikke.platform.grpc.FailedPreconditionException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val ENCRYPTION_KEY_ENV = "NOTIFICATION_REGISTRATION_ENCRYPTION_KEY_BASE64"
private const val AES_KEY_BYTES = 32
private const val NONCE_BYTES = 12
private const val TAG_BITS = 128
private const val ENCRYPTED_VALUE_VERSION: Byte = 1
private const val TRANSFORMATION = "AES/GCM/NoPadding"

class PushRegistrationCipher private constructor(
    private val key: SecretKey,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun hash(installationId: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(installationId.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun encrypt(installationId: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val ciphertext =
            Cipher
                .getInstance(TRANSFORMATION)
                .apply {
                    init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                    updateAAD(ADDITIONAL_AUTHENTICATED_DATA)
                }.doFinal(installationId.toByteArray(StandardCharsets.UTF_8))

        return Base64
            .getEncoder()
            .encodeToString(byteArrayOf(ENCRYPTED_VALUE_VERSION) + nonce + ciphertext)
    }

    fun decrypt(encryptedInstallationId: String): String {
        val payload =
            try {
                Base64.getDecoder().decode(encryptedInstallationId)
            } catch (exception: IllegalArgumentException) {
                throw FailedPreconditionException("Stored push registration is invalid", exception)
            }
        if (payload.size <= 1 + NONCE_BYTES || payload.first() != ENCRYPTED_VALUE_VERSION) {
            throw FailedPreconditionException("Stored push registration is invalid")
        }

        val nonce = payload.copyOfRange(1, 1 + NONCE_BYTES)
        val ciphertext = payload.copyOfRange(1 + NONCE_BYTES, payload.size)
        return try {
            String(
                Cipher
                    .getInstance(TRANSFORMATION)
                    .apply {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                        updateAAD(ADDITIONAL_AUTHENTICATED_DATA)
                    }.doFinal(ciphertext),
                StandardCharsets.UTF_8,
            )
        } catch (exception: Exception) {
            throw FailedPreconditionException("Stored push registration could not be decrypted", exception)
        }
    }

    companion object {
        private val ADDITIONAL_AUTHENTICATED_DATA =
            "mikke-push-registration-v1".toByteArray(StandardCharsets.UTF_8)

        fun fromEnvironment(): PushRegistrationCipher {
            val encodedKey =
                System
                    .getenv(ENCRYPTION_KEY_ENV)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: error("$ENCRYPTION_KEY_ENV is not configured")
            val keyBytes =
                try {
                    Base64.getDecoder().decode(encodedKey)
                } catch (exception: IllegalArgumentException) {
                    error("$ENCRYPTION_KEY_ENV must be valid base64: ${exception.message}")
                }
            require(keyBytes.size == AES_KEY_BYTES) {
                "$ENCRYPTION_KEY_ENV must decode to $AES_KEY_BYTES bytes"
            }
            return fromKeyBytes(keyBytes)
        }

        internal fun fromKeyBytes(keyBytes: ByteArray): PushRegistrationCipher {
            require(keyBytes.size == AES_KEY_BYTES) { "Push registration encryption key must be $AES_KEY_BYTES bytes" }
            return PushRegistrationCipher(SecretKeySpec(keyBytes.copyOf(), "AES"))
        }
    }
}
