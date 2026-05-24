package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.services.media.model.MediaVariantKind
import java.security.SecureRandom
import java.util.Base64

object MediaObjectKeys {
    private const val KEY_BYTE_LENGTH = 32
    private val secureRandom = SecureRandom()

    fun forVariant(variant: MediaVariantKind): String = "media/${variant.name.lowercase()}/${randomToken()}"

    private fun randomToken(): String {
        val bytes = ByteArray(KEY_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }
}
