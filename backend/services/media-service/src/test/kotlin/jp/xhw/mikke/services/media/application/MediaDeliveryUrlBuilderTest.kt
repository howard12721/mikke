package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.services.media.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MediaDeliveryUrlBuilderTest {
    private val objectStorage = FakeDeliveryObjectStorageClient()
    private val builder =
        MediaDeliveryUrlBuilder(
            objectStorageClient = objectStorage,
            expiresIn = 15.minutes,
        )

    @Test
    fun `falls back thumbnail signed url to original when thumbnail is pending`() {
        val media = sampleMedia(thumbnailStatus = MediaVariantStatus.PENDING)

        val urls = builder.buildForMedia(media)

        assertEquals("https://signed.example/media/test/original?expires=900", urls.originalUrl)
        assertEquals(urls.originalUrl, urls.thumbnailUrl)
    }

    @Test
    fun `uses thumbnail signed url when thumbnail is ready`() {
        val media = sampleMedia(thumbnailStatus = MediaVariantStatus.READY)

        val urls = builder.buildForMedia(media)

        assertEquals("https://signed.example/media/test/original?expires=900", urls.originalUrl)
        assertEquals("https://signed.example/media/test/thumbnail?expires=900", urls.thumbnailUrl)
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

private class FakeDeliveryObjectStorageClient : ObjectStorageClient {
    override fun createPresignedPutUrl(
        objectKey: String,
        contentType: String,
        contentLengthBytes: Long,
        expiresIn: kotlin.time.Duration,
    ): PresignedUpload = error("not used")

    override fun createPresignedGetUrl(
        objectKey: String,
        expiresIn: kotlin.time.Duration,
    ): PresignedDownload =
        PresignedDownload(
            url = "https://signed.example/$objectKey?expires=${expiresIn.inWholeSeconds}",
            expiresAt = Instant.fromEpochSeconds(1_700_000_000 + expiresIn.inWholeSeconds),
        )

    override fun headObject(objectKey: String): StoredObjectMetadata? = error("not used")

    override fun getObject(
        objectKey: String,
        maxContentLengthBytes: Long?,
    ): StoredObject? = error("not used")

    override fun putObject(
        objectKey: String,
        contentType: String,
        bytes: ByteArray,
    ): StoredObjectMetadata = error("not used")
}
