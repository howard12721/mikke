package jp.xhw.mikke.services.media.model

import kotlin.time.Instant

data class MediaRecord(
    val id: MediaId,
    val uploaderUserId: UploaderUserId,
    val objectKey: String,
    val contentType: String,
    val contentLengthBytes: Long,
    val etag: String?,
    val status: MediaStatus,
    val uploadMethod: UploadMethod,
    val createdAt: Instant,
    val uploadedAt: Instant?,
    val deletedAt: Instant?,
    val variants: List<MediaVariantRecord>,
)

data class MediaVariantRecord(
    val id: MediaVariantId,
    val mediaId: MediaId,
    val variant: MediaVariantKind,
    val deliveryKey: String,
    val objectKey: String,
    val status: MediaVariantStatus,
    val width: Int?,
    val height: Int?,
    val contentType: String,
    val contentLengthBytes: Long?,
    val createdAt: Instant,
    val readyAt: Instant?,
)
