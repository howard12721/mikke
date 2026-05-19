package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.services.media.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MediaDeliveryUrlBuilderTest {
    private val builder = MediaDeliveryUrlBuilder("https://media.mikke.pics")

    @Test
    fun `builds delivery url from delivery key`() {
        assertEquals(
            "https://media.mikke.pics/media/abc123",
            builder.build("abc123"),
        )
    }

    @Test
    fun `falls back thumbnail url to original when thumbnail is pending`() {
        val media = sampleMedia(thumbnailStatus = MediaVariantStatus.PENDING)

        val urls = builder.buildForMedia(media)

        assertEquals("https://media.mikke.pics/media/original-key", urls.originalUrl)
        assertEquals(urls.originalUrl, urls.thumbnailUrl)
    }

    @Test
    fun `uses thumbnail delivery key when thumbnail is ready`() {
        val media = sampleMedia(thumbnailStatus = MediaVariantStatus.READY)

        val urls = builder.buildForMedia(media)

        assertEquals("https://media.mikke.pics/media/original-key", urls.originalUrl)
        assertEquals("https://media.mikke.pics/media/thumbnail-key", urls.thumbnailUrl)
    }

    private fun sampleMedia(thumbnailStatus: MediaVariantStatus): MediaRecord {
        val mediaId = MediaId(Uuid.parse("00000000-0000-4000-8000-000000000001"))
        val now = Instant.fromEpochSeconds(1_700_000_000, 0)
        return MediaRecord(
            id = mediaId,
            uploaderUserId = UploaderUserId(Uuid.parse("00000000-0000-4000-8000-000000000002")),
            objectKey = "media/test/original",
            contentType = "image/jpeg",
            contentLengthBytes = 1024,
            etag = null,
            status = MediaStatus.PENDING_UPLOAD,
            uploadMethod = UploadMethod.PUT,
            createdAt = now,
            uploadedAt = null,
            deletedAt = null,
            variants =
                listOf(
                    MediaVariantRecord(
                        id = MediaVariantId(Uuid.random()),
                        mediaId = mediaId,
                        variant = MediaVariantKind.ORIGINAL,
                        deliveryKey = "original-key",
                        objectKey = "media/test/original",
                        status = MediaVariantStatus.PENDING,
                        width = null,
                        height = null,
                        contentType = "image/jpeg",
                        contentLengthBytes = null,
                        createdAt = now,
                        readyAt = null,
                    ),
                    MediaVariantRecord(
                        id = MediaVariantId(Uuid.random()),
                        mediaId = mediaId,
                        variant = MediaVariantKind.THUMBNAIL,
                        deliveryKey = "thumbnail-key",
                        objectKey = "media/test/thumbnail",
                        status = thumbnailStatus,
                        width = null,
                        height = null,
                        contentType = "image/jpeg",
                        contentLengthBytes = null,
                        createdAt = now,
                        readyAt = null,
                    ),
                ),
        )
    }
}
