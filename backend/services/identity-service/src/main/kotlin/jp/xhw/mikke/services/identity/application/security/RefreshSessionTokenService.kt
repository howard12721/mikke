package jp.xhw.mikke.services.identity.application.security

import jp.xhw.mikke.platform.auth.IssuedToken
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class RefreshSessionTokenService(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val refreshTokenTtl: Duration = 30.days,
) {
    fun issueRefreshToken(issuedAt: Instant): IssuedToken {
        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)

        return IssuedToken(
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes),
            expiresAt = issuedAt + refreshTokenTtl,
        )
    }

    fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
