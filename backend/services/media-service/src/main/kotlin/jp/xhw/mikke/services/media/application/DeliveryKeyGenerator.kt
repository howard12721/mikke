package jp.xhw.mikke.services.media.application

import java.security.SecureRandom
import java.util.Base64

object DeliveryKeyGenerator {
    private const val KEY_BYTE_LENGTH = 32
    private val secureRandom = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(KEY_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }
}
