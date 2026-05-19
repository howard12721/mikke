package jp.xhw.mikke.services.media

import jp.xhw.mikke.services.media.application.MediaDeliveryUrlBuilder

object MediaServiceConfig {
    fun deliveryUrlBuilder(): MediaDeliveryUrlBuilder {
        val baseUrl =
            System.getenv("MEDIA_DELIVERY_BASE_URL")
                ?: System.getenv("OBJECT_STORAGE_PUBLIC_ENDPOINT")
                ?: "http://localhost:3900"
        return MediaDeliveryUrlBuilder(baseUrl = baseUrl)
    }
}
