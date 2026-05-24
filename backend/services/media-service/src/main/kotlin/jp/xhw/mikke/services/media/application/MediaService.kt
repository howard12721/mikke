package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.services.media.application.port.MediaOutbox
import jp.xhw.mikke.services.media.model.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import jp.xhw.mikke.media.v1.UploadStatus as ProtoUploadStatus

data class CreateUploadUrlCommand(
    val contentType: String,
    val contentLengthBytes: Long,
    val originalFileName: String?,
    val uploaderUserId: UploaderUserId,
)

data class CreateUploadUrlResult(
    val mediaId: MediaId,
    val objectKey: String,
    val uploadUrl: String,
    val uploadMethod: UploadMethod,
    val requiredHeaders: Map<String, String>,
    val expiresAt: Instant,
)

data class CheckUploadCommand(
    val mediaId: MediaId,
    val objectKey: String,
)

data class CheckUploadResult(
    val mediaId: MediaId,
    val objectKey: String,
    val status: ProtoUploadStatus,
    val contentLengthBytes: Long,
    val contentType: String,
    val etag: String,
)

data class MediaView(
    val record: MediaRecord,
    val deliveryUrls: MediaDeliveryUrls,
)

class MediaService(
    private val mediaRepository: MediaRepository,
    private val mediaOutbox: MediaOutbox,
    private val objectStorageClient: ObjectStorageClient,
    private val deliveryUrlBuilder: MediaDeliveryUrlBuilder,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock = Clock.System,
    private val uploadUrlTtl: kotlin.time.Duration = 15.minutes,
) {
    fun createUploadUrl(command: CreateUploadUrlCommand): CreateUploadUrlResult =
        transactionRunner.runInTransaction {
            val contentType = command.contentType.trim()
            MediaContentPolicy.validateContentType(contentType)
            MediaContentPolicy.validateContentLength(command.contentLengthBytes)

            val now = clock.now()
            val mediaId = MediaId(Uuid.random())
            val originalObjectKey = MediaObjectKeys.forVariant(MediaVariantKind.ORIGINAL)
            val thumbnailObjectKey = MediaObjectKeys.forVariant(MediaVariantKind.THUMBNAIL)

            val originalVariant =
                MediaVariantRecord(
                    id = MediaVariantId(Uuid.random()),
                    mediaId = mediaId,
                    variant = MediaVariantKind.ORIGINAL,
                    objectKey = originalObjectKey,
                    status = MediaVariantStatus.PENDING,
                    width = null,
                    height = null,
                    contentType = contentType,
                    contentLengthBytes = null,
                    createdAt = now,
                    readyAt = null,
                )

            val thumbnailVariant =
                MediaVariantRecord(
                    id = MediaVariantId(Uuid.random()),
                    mediaId = mediaId,
                    variant = MediaVariantKind.THUMBNAIL,
                    objectKey = thumbnailObjectKey,
                    status = MediaVariantStatus.PENDING,
                    width = null,
                    height = null,
                    contentType = contentType,
                    contentLengthBytes = null,
                    createdAt = now,
                    readyAt = null,
                )

            val media =
                MediaRecord(
                    id = mediaId,
                    uploaderUserId = command.uploaderUserId,
                    objectKey = originalObjectKey,
                    contentType = contentType,
                    contentLengthBytes = command.contentLengthBytes,
                    etag = null,
                    status = MediaStatus.PENDING_UPLOAD,
                    uploadMethod = UploadMethod.PUT,
                    createdAt = now,
                    uploadedAt = null,
                    deletedAt = null,
                    variants = listOf(originalVariant, thumbnailVariant),
                )

            mediaRepository.insert(media)

            val presigned =
                objectStorageClient.createPresignedPutUrl(
                    objectKey = originalObjectKey,
                    contentType = contentType,
                    contentLengthBytes = command.contentLengthBytes,
                    expiresIn = uploadUrlTtl,
                )

            mediaOutbox.appendUploadUrlCreated(media, presigned.expiresAt)

            CreateUploadUrlResult(
                mediaId = mediaId,
                objectKey = originalObjectKey,
                uploadUrl = presigned.uploadUrl,
                uploadMethod = UploadMethod.PUT,
                requiredHeaders = presigned.requiredHeaders,
                expiresAt = presigned.expiresAt,
            )
        }

    fun checkUpload(
        command: CheckUploadCommand,
        requesterUserId: UploaderUserId,
    ): CheckUploadResult =
        transactionRunner.runInTransaction {
            val media =
                mediaRepository.findById(command.mediaId)
                    ?: throw MediaNotFoundException()

            if (media.status == MediaStatus.DELETED) {
                throw MediaNotFoundException()
            }

            if (media.uploaderUserId != requesterUserId) {
                throw MediaAccessDeniedException()
            }

            if (media.objectKey != command.objectKey) {
                return@runInTransaction invalidCheckUploadResult(command)
            }

            if (media.status == MediaStatus.READY) {
                return@runInTransaction uploadedCheckUploadResult(media)
            }

            val stored =
                objectStorageClient.headObject(command.objectKey)
                    ?: return@runInTransaction pendingCheckUploadResult(command, media)

            if (!stored.contentType.equals(media.contentType, ignoreCase = true)) {
                return@runInTransaction invalidCheckUploadResult(command)
            }

            if (stored.contentLengthBytes != media.contentLengthBytes) {
                return@runInTransaction invalidCheckUploadResult(command)
            }

            val now = clock.now()
            val originalVariant =
                media.variants.first { it.variant == MediaVariantKind.ORIGINAL }
            val updatedOriginalVariant =
                originalVariant.copy(
                    status = MediaVariantStatus.READY,
                    contentLengthBytes = stored.contentLengthBytes,
                    readyAt = now,
                )
            val updatedVariants =
                media.variants.map { variant ->
                    if (variant.variant == MediaVariantKind.ORIGINAL) {
                        updatedOriginalVariant
                    } else {
                        variant
                    }
                }

            val updatedMedia =
                media.copy(
                    status = MediaStatus.READY,
                    etag = stored.etag,
                    uploadedAt = now,
                    variants = updatedVariants,
                )

            mediaRepository.update(updatedMedia)
            mediaOutbox.appendUploadCompleted(updatedMedia)

            uploadedCheckUploadResult(updatedMedia)
        }

    fun getMedia(mediaId: MediaId): MediaView =
        transactionRunner.runInTransaction {
            val media =
                mediaRepository.findById(mediaId)
                    ?: throw MediaNotFoundException()

            if (media.status == MediaStatus.DELETED) {
                throw MediaNotFoundException()
            }

            MediaView(
                record = media,
                deliveryUrls = deliveryUrlBuilder.buildForMedia(media),
            )
        }

    fun batchGetMedia(mediaIds: List<MediaId>): List<MediaView> =
        transactionRunner.runInTransaction {
            if (mediaIds.isEmpty()) {
                return@runInTransaction emptyList()
            }

            mediaRepository
                .findByIds(mediaIds)
                .filter { it.status != MediaStatus.DELETED }
                .map { media ->
                    MediaView(
                        record = media,
                        deliveryUrls = deliveryUrlBuilder.buildForMedia(media),
                    )
                }
        }

    fun deleteMedia(
        mediaId: MediaId,
        requesterUserId: UploaderUserId,
    ) {
        transactionRunner.runInTransaction {
            val media =
                mediaRepository.findById(mediaId)
                    ?: throw MediaNotFoundException()

            if (media.uploaderUserId != requesterUserId) {
                throw MediaAccessDeniedException()
            }

            if (media.status == MediaStatus.DELETED) {
                return@runInTransaction
            }

            val now = clock.now()
            val deleted =
                media.copy(
                    status = MediaStatus.DELETED,
                    deletedAt = now,
                )

            mediaRepository.update(deleted)
            mediaOutbox.appendDeleted(mediaId, now)
        }
    }

    private fun pendingCheckUploadResult(
        command: CheckUploadCommand,
        media: MediaRecord,
    ): CheckUploadResult =
        CheckUploadResult(
            mediaId = command.mediaId,
            objectKey = command.objectKey,
            status = ProtoUploadStatus.UPLOAD_STATUS_PENDING,
            contentLengthBytes = media.contentLengthBytes,
            contentType = media.contentType,
            etag = "",
        )

    private fun invalidCheckUploadResult(command: CheckUploadCommand): CheckUploadResult =
        CheckUploadResult(
            mediaId = command.mediaId,
            objectKey = command.objectKey,
            status = ProtoUploadStatus.UPLOAD_STATUS_INVALID,
            contentLengthBytes = 0,
            contentType = "",
            etag = "",
        )

    private fun uploadedCheckUploadResult(media: MediaRecord): CheckUploadResult =
        CheckUploadResult(
            mediaId = media.id,
            objectKey = media.objectKey,
            status = ProtoUploadStatus.UPLOAD_STATUS_UPLOADED,
            contentLengthBytes = media.contentLengthBytes,
            contentType = media.contentType,
            etag = media.etag.orEmpty(),
        )
}

fun parseMediaId(raw: String): MediaId = MediaId(parseGrpcUuid(raw, fieldName = "media_id"))
