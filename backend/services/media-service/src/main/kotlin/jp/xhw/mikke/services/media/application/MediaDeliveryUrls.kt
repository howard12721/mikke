package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.services.media.model.MediaRecord
import jp.xhw.mikke.services.media.model.MediaVariantKind
import jp.xhw.mikke.services.media.model.MediaVariantStatus
import kotlin.time.Duration

data class MediaDeliveryUrls(
    val originalUrl: String,
    val thumbnailUrl: String,
    val iconUrl: String?,
)

class MediaDeliveryUrlBuilder(
    private val objectStorageClient: ObjectStorageClient,
    private val expiresIn: Duration,
) {
    fun buildForMedia(media: MediaRecord): MediaDeliveryUrls {
        val originalVariant =
            media.variants.first { it.variant == MediaVariantKind.ORIGINAL }
        val thumbnailVariant =
            media.variants.firstOrNull { it.variant == MediaVariantKind.THUMBNAIL }
        val iconVariant =
            media.variants.firstOrNull { it.variant == MediaVariantKind.ICON }

        val originalUrl = sign(originalVariant.objectKey)
        val thumbnailUrl =
            if (thumbnailVariant?.status == MediaVariantStatus.READY) {
                sign(thumbnailVariant.objectKey)
            } else {
                originalUrl
            }
        val iconUrl =
            if (iconVariant?.status == MediaVariantStatus.READY) {
                sign(iconVariant.objectKey)
            } else {
                null
            }

        return MediaDeliveryUrls(
            originalUrl = originalUrl,
            thumbnailUrl = thumbnailUrl,
            iconUrl = iconUrl,
        )
    }

    private fun sign(objectKey: String): String =
        objectStorageClient
            .createPresignedGetUrl(objectKey = objectKey, expiresIn = expiresIn)
            .url
}
