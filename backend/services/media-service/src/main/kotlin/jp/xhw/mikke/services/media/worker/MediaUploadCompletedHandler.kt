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
    private val thumbnailGenerator: ThumbnailGenerator = ScrimageThumbnailGenerator(),
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
                        VariantWork.Done
                    }

                    media == null -> {
                        VariantWork.MarkProcessed(eventId, eventType)
                    }

                    media.status == MediaStatus.DELETED -> {
                        VariantWork.MarkProcessed(eventId, eventType)
                    }

                    media.status != MediaStatus.READY -> {
                        VariantWork.MarkProcessed(eventId, eventType)
                    }

                    media.objectKey != objectKey -> {
                        VariantWork.MarkProcessed(eventId, eventType)
                    }

                    media.contentLengthBytes > maxOriginalBytes -> {
                        VariantWork.FailVariant(
                            eventId = eventId,
                            eventType = eventType,
                            mediaId = mediaId,
                            reason = "Original object exceeds thumbnail input limit: ${media.contentLengthBytes} bytes",
                        )
                    }

                    else -> {
                        val generatedVariant =
                            media.variants.firstOrNull { it.variant != MediaVariantKind.ORIGINAL }
                                ?: return@runInTransaction VariantWork.MarkProcessed(eventId, eventType)
                        if (generatedVariant.status == MediaVariantStatus.READY || generatedVariant.status == MediaVariantStatus.FAILED) {
                            VariantWork.MarkProcessed(eventId, eventType)
                        } else {
                            VariantWork.Generate(
                                eventId = eventId,
                                eventType = eventType,
                                mediaId = mediaId,
                                originalObjectKey = media.objectKey,
                                targetVariantKind = generatedVariant.variant,
                                targetObjectKey = generatedVariant.objectKey,
                            )
                        }
                    }
                }
            }

        when (work) {
            VariantWork.Done -> {
                Unit
            }

            is VariantWork.MarkProcessed -> {
                markProcessed(work.eventId, work.eventType)
            }

            is VariantWork.FailVariant -> {
                markVariantFailed(
                    work.mediaId,
                    work.eventId,
                    work.eventType,
                    work.reason,
                )
            }

            is VariantWork.Generate -> {
                generateVariant(work)
            }
        }
    }

    private fun generateVariant(work: VariantWork.Generate) {
        val original =
            try {
                objectStorageClient.getObject(work.originalObjectKey, maxContentLengthBytes = maxOriginalBytes)
                    ?: run {
                        markVariantFailed(
                            mediaId = work.mediaId,
                            eventId = work.eventId,
                            eventType = work.eventType,
                            reason = "Original object not found: ${work.originalObjectKey}",
                        )
                        return
                    }
            } catch (e: ObjectTooLargeException) {
                markVariantFailed(
                    work.mediaId,
                    work.eventId,
                    work.eventType,
                    e.message ?: "Original object too large",
                )
                return
            }

        val generated =
            try {
                thumbnailGenerator.generateWebp(
                    sourceBytes = original.bytes,
                    maxSizePx = maxSizePx,
                    targetAspectWidth = work.targetVariantKind.aspectWidth(),
                    targetAspectHeight = work.targetVariantKind.aspectHeight(),
                )
            } catch (e: UnsupportedImageException) {
                markVariantFailed(work.mediaId, work.eventId, work.eventType, e.message ?: "Unsupported image")
                return
            }

        val stored =
            objectStorageClient.putObject(
                objectKey = work.targetObjectKey,
                contentType = THUMBNAIL_CONTENT_TYPE,
                bytes = generated.bytes,
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

            val currentGeneratedVariant =
                current.variants.firstOrNull { it.variant == work.targetVariantKind }
                    ?: run {
                        processedEventStore.tryMarkProcessed(work.eventId, work.eventType)
                        return@runInTransaction
                    }

            if (currentGeneratedVariant.status == MediaVariantStatus.READY) {
                processedEventStore.tryMarkProcessed(work.eventId, work.eventType)
                return@runInTransaction
            }

            val now = clock.now()
            val readyVariant =
                currentGeneratedVariant.copy(
                    status = MediaVariantStatus.READY,
                    width = generated.width,
                    height = generated.height,
                    contentType = stored.contentType,
                    contentLengthBytes = stored.contentLengthBytes,
                    readyAt = now,
                )
            mediaRepository.updateVariant(readyVariant)
            mediaOutbox.appendVariantReady(readyVariant)
            processedEventStore.tryMarkProcessed(work.eventId, work.eventType)
        }
    }

    private fun markVariantFailed(
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
            val currentGeneratedVariant = current?.variants?.firstOrNull { it.variant != MediaVariantKind.ORIGINAL }
            if (currentGeneratedVariant != null && currentGeneratedVariant.status == MediaVariantStatus.PENDING) {
                mediaRepository.updateVariant(
                    currentGeneratedVariant.copy(
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
        logger.warning("Marked media derived-image generation failed for event $eventId: $reason")
    }

    private fun markProcessed(
        eventId: Uuid,
        eventType: String,
    ) {
        transactionRunner.runInTransaction {
            processedEventStore.tryMarkProcessed(eventId, eventType)
        }
    }

    private sealed interface VariantWork {
        data object Done : VariantWork

        data class MarkProcessed(
            val eventId: Uuid,
            val eventType: String,
        ) : VariantWork

        data class Generate(
            val eventId: Uuid,
            val eventType: String,
            val mediaId: MediaId,
            val originalObjectKey: String,
            val targetVariantKind: MediaVariantKind,
            val targetObjectKey: String,
        ) : VariantWork

        data class FailVariant(
            val eventId: Uuid,
            val eventType: String,
            val mediaId: MediaId,
            val reason: String,
        ) : VariantWork
    }

    private companion object {
        const val THUMBNAIL_CONTENT_TYPE = "image/webp"
    }
}

private fun MediaVariantKind.aspectWidth(): Int =
    when (this) {
        MediaVariantKind.THUMBNAIL -> 16
        MediaVariantKind.ICON -> 1
        MediaVariantKind.ORIGINAL -> error("ORIGINAL variant does not have derived aspect ratio")
    }

private fun MediaVariantKind.aspectHeight(): Int =
    when (this) {
        MediaVariantKind.THUMBNAIL -> 9
        MediaVariantKind.ICON -> 1
        MediaVariantKind.ORIGINAL -> error("ORIGINAL variant does not have derived aspect ratio")
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
