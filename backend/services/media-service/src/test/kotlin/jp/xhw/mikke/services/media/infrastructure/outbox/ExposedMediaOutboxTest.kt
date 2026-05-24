package jp.xhw.mikke.services.media.infrastructure.outbox

import jp.xhw.mikke.events.media.MediaEventTypes
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.media.model.MediaId
import jp.xhw.mikke.services.media.model.MediaRecord
import jp.xhw.mikke.services.media.model.MediaStatus
import jp.xhw.mikke.services.media.model.MediaVariantId
import jp.xhw.mikke.services.media.model.MediaVariantKind
import jp.xhw.mikke.services.media.model.MediaVariantRecord
import jp.xhw.mikke.services.media.model.MediaVariantStatus
import jp.xhw.mikke.services.media.model.UploadMethod
import jp.xhw.mikke.services.media.model.UploaderUserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedMediaOutboxTest {
    @Test
    fun `append upload url created writes outbox entry with payload`() =
        withMediaOutboxTable {
            val createdAt = Instant.parse("2026-05-18T01:02:03.456789Z")
            val expiresAt = Instant.parse("2026-05-18T01:17:03.456789Z")
            val media = mediaRecord(createdAt = createdAt)
            val outbox = ExposedMediaOutbox(FixedClock(Instant.parse("2026-05-18T01:02:04Z")))

            outbox.appendUploadUrlCreated(media, expiresAt)

            val row = MediaOutboxTable.selectAll().single()
            assertEquals(MediaEventTypes.UPLOAD_URL_CREATED, row[MediaOutboxTable.eventType])
            assertEquals(1, row[MediaOutboxTable.eventVersion])
            assertEquals("media", row[MediaOutboxTable.aggregateType])
            assertEquals(media.id.value, row[MediaOutboxTable.aggregateId])
            assertEquals(Instant.parse("2026-05-18T01:02:04Z"), row[MediaOutboxTable.createdAt].toKotlinInstant())

            val payload = row[MediaOutboxTable.payloadJson].parseJsonObject()
            assertEquals(formatGrpcUuid(media.id.value), payload.string("media_id"))
            assertEquals(formatGrpcUuid(media.uploaderUserId.value), payload.string("uploader_user_id"))
            assertEquals(media.objectKey, payload.string("object_key"))
            assertEquals(media.contentType, payload.string("content_type"))
            assertEquals(media.contentLengthBytes.toString(), payload.string("content_length_bytes"))
            assertEquals("2026-05-18T01:17:03.456789Z", payload.string("expires_at"))
            assertEquals(
                setOf("media_id", "uploader_user_id", "object_key", "content_type", "content_length_bytes", "expires_at"),
                payload.keys,
            )
        }

    @Test
    fun `append upload completed writes outbox entry with payload`() =
        withMediaOutboxTable {
            val uploadedAt = Instant.parse("2026-05-18T02:03:04.123456Z")
            val media =
                mediaRecord(
                    status = MediaStatus.READY,
                    etag = "\"etag-123\"",
                    uploadedAt = uploadedAt,
                )
            val outbox = ExposedMediaOutbox(FixedClock(Instant.parse("2026-05-18T02:03:05Z")))

            outbox.appendUploadCompleted(media)

            val row = MediaOutboxTable.selectAll().single()
            assertEquals(MediaEventTypes.UPLOAD_COMPLETED, row[MediaOutboxTable.eventType])
            assertEquals(1, row[MediaOutboxTable.eventVersion])
            assertEquals("media", row[MediaOutboxTable.aggregateType])
            assertEquals(media.id.value, row[MediaOutboxTable.aggregateId])
            assertEquals(Instant.parse("2026-05-18T02:03:05Z"), row[MediaOutboxTable.createdAt].toKotlinInstant())

            val payload = row[MediaOutboxTable.payloadJson].parseJsonObject()
            assertEquals(formatGrpcUuid(media.id.value), payload.string("media_id"))
            assertEquals(formatGrpcUuid(media.uploaderUserId.value), payload.string("uploader_user_id"))
            assertEquals(media.objectKey, payload.string("object_key"))
            assertEquals(media.contentType, payload.string("content_type"))
            assertEquals(media.contentLengthBytes.toString(), payload.string("content_length_bytes"))
            assertEquals("\"etag-123\"", payload.string("etag"))
            assertEquals("2026-05-18T02:03:04.123456Z", payload.string("uploaded_at"))
            assertEquals(
                setOf(
                    "media_id",
                    "uploader_user_id",
                    "object_key",
                    "content_type",
                    "content_length_bytes",
                    "etag",
                    "uploaded_at",
                ),
                payload.keys,
            )
        }

    @Test
    fun `append thumbnail ready writes outbox entry with payload`() =
        withMediaOutboxTable {
            val mediaId = MediaId(Uuid.parse("018f2a58-4d65-7c0f-8e01-451a05c0f001"))
            val readyAt = Instant.parse("2026-05-18T04:05:06.123456Z")
            val variant =
                MediaVariantRecord(
                    id = MediaVariantId(Uuid.parse("018f2a58-4d65-7c0f-8e01-451a05c0f002")),
                    mediaId = mediaId,
                    variant = MediaVariantKind.THUMBNAIL,
                    objectKey = "media/thumbnail/thumb-key",
                    status = MediaVariantStatus.READY,
                    width = 512,
                    height = 384,
                    contentType = "image/jpeg",
                    contentLengthBytes = 12345,
                    createdAt = Instant.parse("2026-05-18T04:00:00Z"),
                    readyAt = readyAt,
                )
            val outbox = ExposedMediaOutbox(FixedClock(Instant.parse("2026-05-18T04:05:07Z")))

            outbox.appendThumbnailReady(variant)

            val row = MediaOutboxTable.selectAll().single()
            assertEquals(MediaEventTypes.THUMBNAIL_READY, row[MediaOutboxTable.eventType])
            assertEquals(1, row[MediaOutboxTable.eventVersion])
            assertEquals("media", row[MediaOutboxTable.aggregateType])
            assertEquals(mediaId.value, row[MediaOutboxTable.aggregateId])
            assertEquals(Instant.parse("2026-05-18T04:05:07Z"), row[MediaOutboxTable.createdAt].toKotlinInstant())

            val payload = row[MediaOutboxTable.payloadJson].parseJsonObject()
            assertEquals(formatGrpcUuid(mediaId.value), payload.string("media_id"))
            assertEquals("media/thumbnail/thumb-key", payload.string("object_key"))
            assertEquals("image/jpeg", payload.string("content_type"))
            assertEquals("12345", payload.string("content_length_bytes"))
            assertEquals("512", payload.string("width"))
            assertEquals("384", payload.string("height"))
            assertEquals("2026-05-18T04:05:06.123456Z", payload.string("ready_at"))
            assertEquals(
                setOf("media_id", "object_key", "content_type", "content_length_bytes", "width", "height", "ready_at"),
                payload.keys,
            )
        }

    @Test
    fun `append deleted writes outbox entry with payload`() =
        withMediaOutboxTable {
            val mediaId = MediaId(Uuid.parse("018f2a58-4d65-7c0f-8e01-451a05c0f001"))
            val deletedAt = Instant.parse("2026-05-18T03:04:05.987654Z")
            val outbox = ExposedMediaOutbox(FixedClock(Instant.parse("2026-05-18T03:04:06Z")))

            outbox.appendDeleted(mediaId, deletedAt)

            val row = MediaOutboxTable.selectAll().single()
            assertEquals(MediaEventTypes.DELETED, row[MediaOutboxTable.eventType])
            assertEquals(1, row[MediaOutboxTable.eventVersion])
            assertEquals("media", row[MediaOutboxTable.aggregateType])
            assertEquals(mediaId.value, row[MediaOutboxTable.aggregateId])
            assertEquals(Instant.parse("2026-05-18T03:04:06Z"), row[MediaOutboxTable.createdAt].toKotlinInstant())

            val payload = row[MediaOutboxTable.payloadJson].parseJsonObject()
            assertEquals(formatGrpcUuid(mediaId.value), payload.string("media_id"))
            assertEquals("2026-05-18T03:04:05.987654Z", payload.string("deleted_at"))
            assertEquals(setOf("media_id", "deleted_at"), payload.keys)
        }

    @Test
    fun `append thumbnail ready rejects missing dimensions`() =
        withMediaOutboxTable {
            val outbox = ExposedMediaOutbox(FixedClock(Instant.parse("2026-05-18T04:05:07Z")))
            val variant =
                thumbnailVariant(
                    width = null,
                    height = 384,
                )

            assertThrows(IllegalArgumentException::class.java) {
                outbox.appendThumbnailReady(variant)
            }
        }

    private fun withMediaOutboxTable(block: () -> Unit) {
        val database =
            Database.connect(
                url = "jdbc:h2:mem:${Uuid.random()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )

        transaction(database) {
            SchemaUtils.create(MediaOutboxTable)
            block()
        }
    }

    private fun mediaRecord(
        status: MediaStatus = MediaStatus.PENDING_UPLOAD,
        etag: String? = null,
        createdAt: Instant = Instant.parse("2026-05-18T01:02:03Z"),
        uploadedAt: Instant? = null,
    ): MediaRecord =
        MediaRecord(
            id = MediaId(Uuid.parse("018f2a58-4d65-7c0f-8e01-451a05c0cafe")),
            uploaderUserId = UploaderUserId(Uuid.parse("018f2a58-4d65-7c0f-8e01-451a05c0babe")),
            objectKey = "media/original/018f2a58-4d65-7c0f-8e01-451a05c0cafe",
            contentType = "image/jpeg",
            contentLengthBytes = 123_456,
            etag = etag,
            status = status,
            uploadMethod = UploadMethod.PUT,
            createdAt = createdAt,
            uploadedAt = uploadedAt,
            deletedAt = null,
            variants = emptyList(),
        )

    private fun thumbnailVariant(
        width: Int? = 512,
        height: Int? = 384,
    ): MediaVariantRecord =
        MediaVariantRecord(
            id = MediaVariantId(Uuid.parse("018f2a58-4d65-7c0f-8e01-451a05c0f002")),
            mediaId = MediaId(Uuid.parse("018f2a58-4d65-7c0f-8e01-451a05c0f001")),
            variant = MediaVariantKind.THUMBNAIL,
            objectKey = "media/thumbnail/thumb-key",
            status = MediaVariantStatus.READY,
            width = width,
            height = height,
            contentType = "image/jpeg",
            contentLengthBytes = 12345,
            createdAt = Instant.parse("2026-05-18T04:00:00Z"),
            readyAt = Instant.parse("2026-05-18T04:05:06.123456Z"),
        )

    private fun String.parseJsonObject(): JsonObject = Json.parseToJsonElement(this) as JsonObject

    private fun JsonObject.string(key: String): String = checkNotNull(this[key]).jsonPrimitive.content
}

private class FixedClock(
    private val now: Instant,
) : Clock {
    override fun now(): Instant = now
}
