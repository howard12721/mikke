package jp.xhw.mikke.services.media.worker

import jp.xhw.mikke.events.core.EventEnvelope
import jp.xhw.mikke.events.media.MediaUploadCompletedPayload
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import jp.xhw.mikke.platform.events.EventHandler
import jp.xhw.mikke.platform.events.ProcessedEventStore
import jp.xhw.mikke.platform.events.exposed.ProcessedEventsTable
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.services.media.application.MediaRepository
import jp.xhw.mikke.services.media.application.ObjectStorageClient
import jp.xhw.mikke.services.media.application.ObjectTooLargeException
import jp.xhw.mikke.services.media.application.port.MediaOutbox
import jp.xhw.mikke.services.media.model.MediaId
import jp.xhw.mikke.services.media.model.MediaStatus
import jp.xhw.mikke.services.media.model.MediaVariantKind
import jp.xhw.mikke.services.media.model.MediaVariantStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import java.util.logging.Logger
import kotlin.time.Clock
import kotlin.uuid.Uuid

class MediaUploadCompletedHandler(
    private val mediaRepository: MediaRepository,
    private val mediaOutbox: MediaOutbox,
    private val objectStorageClient: ObjectStorageClient,
    private val transactionRunner: TransactionRunner,
    private val processedEventStore: ProcessedEventStore,
    private val thumbnailGenerator: ThumbnailGenerator = ImageIoThumbnailGenerator(),
    private val maxSizePx: Int = 512,
    private val maxOriginalBytes: Long = 20L * 1024L * 1024L,
    private val clock: Clock = Clock.System,
    private val logger: Logger = Logger.getLogger(MediaUploadCompletedHandler::class.java.name),
) : EventHandler<MediaUploadCompletedPayload> {
    init {
        require(maxSizePx > 0) { "maxSizePx must be positive" }
        require(maxOriginalBytes > 0) { "maxOriginalBytes must be positive" }
    }

    override suspend fun handle(event: EventEnvelope<MediaUploadCompletedPayload>) {
        val eventId = Uuid.parse(event.eventId)
        val eventType = event.eventType
        val mediaId = MediaId(parseGrpcUuid(event.payload.mediaId, fieldName = "media_id"))
        val objectKey = event.payload.objectKey

        if (objectKey.isBlank()) {
            return
        }

        val work =
            transactionRunner.runInTransaction {
                val media = mediaRepository.findById(mediaId)
                when {
                    processedEventStore.exists(eventId) -> {
                        ThumbnailWork.Done
                    }

                    media == null -> {
                        ThumbnailWork.MarkProcessed(eventId, eventType)
                    }

                    media.status == MediaStatus.DELETED -> {
                        ThumbnailWork.MarkProcessed(eventId, eventType)
                    }

                    media.status != MediaStatus.READY -> {
                        ThumbnailWork.MarkProcessed(eventId, eventType)
                    }

                    media.objectKey != objectKey -> {
                        ThumbnailWork.MarkProcessed(eventId, eventType)
                    }

                    media.contentLengthBytes > maxOriginalBytes -> {
                        ThumbnailWork.FailThumbnail(
                            eventId = eventId,
                            eventType = eventType,
                            mediaId = mediaId,
                            reason = "Original object exceeds thumbnail input limit: ${media.contentLengthBytes} bytes",
                        )
                    }

                    else -> {
                        val thumbnail =
                            media.variants.firstOrNull { it.variant == MediaVariantKind.THUMBNAIL }
                                ?: return@runInTransaction ThumbnailWork.MarkProcessed(eventId, eventType)
                        if (thumbnail.status == MediaVariantStatus.READY || thumbnail.status == MediaVariantStatus.FAILED) {
                            ThumbnailWork.MarkProcessed(eventId, eventType)
                        } else {
                            ThumbnailWork.Generate(
                                eventId = eventId,
                                eventType = eventType,
                                mediaId = mediaId,
                                originalObjectKey = media.objectKey,
                                thumbnailObjectKey = thumbnail.objectKey,
                            )
                        }
                    }
                }
            }

        when (work) {
            ThumbnailWork.Done -> {
                Unit
            }

            is ThumbnailWork.MarkProcessed -> {
                markProcessed(work.eventId, work.eventType)
            }

            is ThumbnailWork.FailThumbnail -> {
                markThumbnailFailed(
                    work.mediaId,
                    work.eventId,
                    work.eventType,
                    work.reason,
                )
            }

            is ThumbnailWork.Generate -> {
                generateThumbnail(work)
            }
        }
    }

    private fun generateThumbnail(work: ThumbnailWork.Generate) {
        val original =
            try {
                objectStorageClient.getObject(work.originalObjectKey, maxContentLengthBytes = maxOriginalBytes)
                    ?: run {
                        markThumbnailFailed(
                            mediaId = work.mediaId,
                            eventId = work.eventId,
                            eventType = work.eventType,
                            reason = "Original object not found: ${work.originalObjectKey}",
                        )
                        return
                    }
            } catch (e: ObjectTooLargeException) {
                markThumbnailFailed(
                    work.mediaId,
                    work.eventId,
                    work.eventType,
                    e.message ?: "Original object too large",
                )
                return
            }

        val thumbnail =
            try {
                thumbnailGenerator.generateWebp(
                    sourceBytes = original.bytes,
                    maxSizePx = maxSizePx,
                )
            } catch (e: UnsupportedImageException) {
                markThumbnailFailed(work.mediaId, work.eventId, work.eventType, e.message ?: "Unsupported image")
                return
            }

        val stored =
            objectStorageClient.putObject(
                objectKey = work.thumbnailObjectKey,
                contentType = THUMBNAIL_CONTENT_TYPE,
                bytes = thumbnail.bytes,
            )

        transactionRunner.runInTransaction {
            if (processedEventStore.exists(work.eventId)) {
                return@runInTransaction
            }

            val current = mediaRepository.findById(work.mediaId)
            if (current == null || current.status == MediaStatus.DELETED) {
                processedEventStore.tryMarkProcessed(work.eventId, work.eventType)
                return@runInTransaction
            }

            val currentThumbnail =
                current.variants.firstOrNull { it.variant == MediaVariantKind.THUMBNAIL }
                    ?: run {
                        processedEventStore.tryMarkProcessed(work.eventId, work.eventType)
                        return@runInTransaction
                    }

            if (currentThumbnail.status == MediaVariantStatus.READY) {
                processedEventStore.tryMarkProcessed(work.eventId, work.eventType)
                return@runInTransaction
            }

            val now = clock.now()
            val readyThumbnail =
                currentThumbnail.copy(
                    status = MediaVariantStatus.READY,
                    width = thumbnail.width,
                    height = thumbnail.height,
                    contentType = stored.contentType,
                    contentLengthBytes = stored.contentLengthBytes,
                    readyAt = now,
                )
            mediaRepository.updateVariant(readyThumbnail)
            mediaOutbox.appendThumbnailReady(readyThumbnail)
            processedEventStore.tryMarkProcessed(work.eventId, work.eventType)
        }
    }

    private fun markThumbnailFailed(
        mediaId: MediaId,
        eventId: Uuid,
        eventType: String,
        reason: String,
    ) {
        transactionRunner.runInTransaction {
            if (processedEventStore.exists(eventId)) {
                return@runInTransaction
            }

            val current = mediaRepository.findById(mediaId)
            val currentThumbnail = current?.variants?.firstOrNull { it.variant == MediaVariantKind.THUMBNAIL }
            if (currentThumbnail != null && currentThumbnail.status == MediaVariantStatus.PENDING) {
                mediaRepository.updateVariant(
                    currentThumbnail.copy(
                        status = MediaVariantStatus.FAILED,
                        readyAt = clock.now(),
                    ),
                )
            }
            MediaProcessedEventsTable.tryMarkFailed(
                eventId = eventId,
                eventType = eventType,
                failedAt = clock.now(),
                lastError = reason,
            )
        }
        logger.warning("Marked media thumbnail generation failed for event $eventId: $reason")
    }

    private fun markProcessed(
        eventId: Uuid,
        eventType: String,
    ) {
        transactionRunner.runInTransaction {
            processedEventStore.tryMarkProcessed(eventId, eventType)
        }
    }

    private sealed interface ThumbnailWork {
        data object Done : ThumbnailWork

        data class MarkProcessed(
            val eventId: Uuid,
            val eventType: String,
        ) : ThumbnailWork

        data class Generate(
            val eventId: Uuid,
            val eventType: String,
            val mediaId: MediaId,
            val originalObjectKey: String,
            val thumbnailObjectKey: String,
        ) : ThumbnailWork

        data class FailThumbnail(
            val eventId: Uuid,
            val eventType: String,
            val mediaId: MediaId,
            val reason: String,
        ) : ThumbnailWork
    }

    private companion object {
        const val THUMBNAIL_CONTENT_TYPE = "image/webp"
    }
}

object MediaProcessedEventsTable : ProcessedEventsTable("media_processed_events") {
    val failedAt = timestamp("failed_at").nullable()
    val lastError = varchar("last_error", length = 512).nullable()
}

internal fun MediaProcessedEventsTable.tryMarkFailed(
    eventId: Uuid,
    eventType: String,
    failedAt: kotlin.time.Instant,
    lastError: String,
) {
    try {
        insert {
            it[this.eventId] = eventId
            it[this.eventType] = eventType
            it[processedAt] = failedAt.toJavaInstant()
            it[this.failedAt] = failedAt.toJavaInstant()
            it[this.lastError] = lastError.take(512)
        }
    } catch (e: ExposedSQLException) {
        if (!e.isUniqueConstraintViolation()) {
            throw e
        }
        update({ (this.eventId eq eventId) and (this.failedAt.isNotNull()) }) {
            it[this.eventType] = eventType
            it[processedAt] = failedAt.toJavaInstant()
            it[this.failedAt] = failedAt.toJavaInstant()
            it[this.lastError] = lastError.take(512)
        }
    }
}
