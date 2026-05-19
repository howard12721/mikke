package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.events.media.MediaEventTypes
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.outbox.exposed.OutboxTable
import jp.xhw.mikke.platform.outbox.exposed.insertEntry
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.media.model.MediaId
import jp.xhw.mikke.services.media.model.MediaRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface MediaOutbox {
    fun appendUploadUrlCreated(
        media: MediaRecord,
        expiresAt: Instant,
    )

    fun appendUploadCompleted(media: MediaRecord)

    fun appendDeleted(
        mediaId: MediaId,
        deletedAt: Instant,
    )
}

class ExposedMediaOutbox(
    private val clock: Clock = Clock.System,
) : MediaOutbox {
    override fun appendUploadUrlCreated(
        media: MediaRecord,
        expiresAt: Instant,
    ) {
        val payload =
            UploadUrlCreatedPayload(
                mediaId = formatGrpcUuid(media.id.value),
                uploaderUserId = formatGrpcUuid(media.uploaderUserId.value),
                objectKey = media.objectKey,
                contentType = media.contentType,
                contentLengthBytes = media.contentLengthBytes,
                expiresAt = expiresAt.toString(),
            )

        insert(
            eventType = MediaEventTypes.UPLOAD_URL_CREATED,
            aggregateId = media.id.value,
            payloadJson = json.encodeToString(payload),
        )
    }

    override fun appendUploadCompleted(media: MediaRecord) {
        val uploadedAt =
            media.uploadedAt
                ?: error("uploadedAt is required for upload completed event")

        val payload =
            UploadCompletedPayload(
                mediaId = formatGrpcUuid(media.id.value),
                uploaderUserId = formatGrpcUuid(media.uploaderUserId.value),
                objectKey = media.objectKey,
                contentType = media.contentType,
                contentLengthBytes = media.contentLengthBytes,
                etag = media.etag ?: error("etag is required for upload completed event"),
                uploadedAt = uploadedAt.toString(),
            )

        insert(
            eventType = MediaEventTypes.UPLOAD_COMPLETED,
            aggregateId = media.id.value,
            payloadJson = json.encodeToString(payload),
        )
    }

    override fun appendDeleted(
        mediaId: MediaId,
        deletedAt: Instant,
    ) {
        val payload =
            MediaDeletedPayload(
                mediaId = formatGrpcUuid(mediaId.value),
                deletedAt = deletedAt.toString(),
            )

        insert(
            eventType = MediaEventTypes.DELETED,
            aggregateId = mediaId.value,
            payloadJson = json.encodeToString(payload),
        )
    }

    private fun insert(
        eventType: String,
        aggregateId: Uuid,
        payloadJson: String,
    ) {
        MediaOutboxTable.insertEntry(
            OutboxEntry(
                id = Uuid.random(),
                eventType = eventType,
                aggregateType = AGGREGATE_TYPE,
                aggregateId = aggregateId,
                payloadJson = payloadJson,
                createdAt = clock.now(),
            ),
        )
    }

    private companion object {
        const val AGGREGATE_TYPE = "media"

        val json = Json { encodeDefaults = false }
    }
}

private object MediaOutboxTable : OutboxTable("media_outbox")

@Serializable
private data class UploadUrlCreatedPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("uploader_user_id") val uploaderUserId: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("content_length_bytes") val contentLengthBytes: Long,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
private data class UploadCompletedPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("uploader_user_id") val uploaderUserId: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("content_length_bytes") val contentLengthBytes: Long,
    val etag: String,
    @SerialName("uploaded_at") val uploadedAt: String,
)

@Serializable
private data class MediaDeletedPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("deleted_at") val deletedAt: String,
)
