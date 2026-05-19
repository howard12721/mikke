package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.services.media.model.MediaRecord
import jp.xhw.mikke.services.media.model.MediaVariantKind
import jp.xhw.mikke.services.media.model.MediaVariantStatus

data class MediaDeliveryUrls(
    val originalUrl: String,
    val thumbnailUrl: String,
)

class MediaDeliveryUrlBuilder(
    private val baseUrl: String,
) {
    fun build(deliveryKey: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        return "$normalizedBase/media/$deliveryKey"
    }

    fun buildForMedia(media: MediaRecord): MediaDeliveryUrls {
        val originalVariant =
            media.variants.first { it.variant == MediaVariantKind.ORIGINAL }
        val thumbnailVariant =
            media.variants.first { it.variant == MediaVariantKind.THUMBNAIL }

        val originalUrl = build(originalVariant.deliveryKey)
        val thumbnailUrl =
            if (thumbnailVariant.status == MediaVariantStatus.READY) {
                build(thumbnailVariant.deliveryKey)
            } else {
                originalUrl
            }

        return MediaDeliveryUrls(
            originalUrl = originalUrl,
            thumbnailUrl = thumbnailUrl,
        )
    }
}
