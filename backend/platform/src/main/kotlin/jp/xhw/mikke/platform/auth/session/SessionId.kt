package jp.xhw.mikke.platform.auth.session

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

object SessionId {
    const val BYTE_LENGTH = 32
    const val ENCODED_LENGTH = 43
    const val HASH_HEX_LENGTH = 64

    private val base64UrlPattern = Regex("""^[A-Za-z0-9_-]{43}$""")
    private val hashHexPattern = Regex("""^[0-9a-f]{64}$""")

    fun generate(secureRandom: SecureRandom = SecureRandom()): String {
        val tokenBytes = ByteArray(BYTE_LENGTH)
        secureRandom.nextBytes(tokenBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
    }

    fun hash(sessionId: String): String {
        require(isValidSessionId(sessionId)) { "Invalid session id format" }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(sessionId.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun isValidSessionId(value: String): Boolean = value.length == ENCODED_LENGTH && base64UrlPattern.matches(value)

    fun isValidSessionHash(value: String): Boolean = value.length == HASH_HEX_LENGTH && hashHexPattern.matches(value)
}
