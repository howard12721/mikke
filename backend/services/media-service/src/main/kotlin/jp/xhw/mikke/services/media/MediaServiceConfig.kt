package jp.xhw.mikke.services.media

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object MediaServiceConfig {
    fun deliveryUrlTtl(): kotlin.time.Duration =
        System.getenv("MEDIA_DELIVERY_URL_TTL_SECONDS")
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.seconds
            ?: 15.minutes
}
