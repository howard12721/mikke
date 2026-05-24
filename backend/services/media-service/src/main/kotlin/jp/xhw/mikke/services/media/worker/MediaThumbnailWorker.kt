package jp.xhw.mikke.services.media.worker

import jp.xhw.mikke.events.media.MediaEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import jp.xhw.mikke.platform.events.exposed.ProcessedEventsTable
import jp.xhw.mikke.platform.events.exposed.exists
import jp.xhw.mikke.platform.events.exposed.tryMarkProcessed
import jp.xhw.mikke.platform.redis.RedisStreamConsumerGroup
import jp.xhw.mikke.platform.redis.RedisStreamRecord
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
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class MediaThumbnailWorker(
    private val consumerGroup: MediaThumbnailEventConsumer,
    private val mediaRepository: MediaRepository,
    private val mediaOutbox: MediaOutbox,
    private val objectStorageClient: ObjectStorageClient,
    private val transactionRunner: TransactionRunner,
    private val thumbnailGenerator: ThumbnailGenerator = ImageIoThumbnailGenerator(),
    private val maxSizePx: Int = 512,
    private val maxOriginalBytes: Long = 20L * 1024L * 1024L,
    private val readCount: Long = 10,
    private val consumerGroupStartId: String = "$",
    private val staleMinIdle: Duration = Duration.ofMinutes(5),
    private val idleDelay: kotlin.time.Duration = 500.milliseconds,
    private val errorDelay: kotlin.time.Duration = 5.seconds,
    private val clock: Clock = Clock.System,
    private val logger: Logger = Logger.getLogger(MediaThumbnailWorker::class.java.name),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    init {
        require(maxSizePx > 0) { "maxSizePx must be positive" }
        require(maxOriginalBytes > 0) { "maxOriginalBytes must be positive" }
        require(readCount > 0) { "readCount must be positive" }
        require(consumerGroupStartId.isNotBlank()) { "consumerGroupStartId must not be blank" }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) {
            return
        }

        consumerGroup.ensureGroup(startId = consumerGroupStartId)
        job =
            scope.launch {
                while (isActive) {
                    try {
                        val claimed = consumerGroup.claimStale(minIdle = staleMinIdle, count = readCount)
                        val read = consumerGroup.read(count = readCount)
                        val records = (claimed + read).distinctBy { it.id }

                        if (records.isEmpty()) {
                            delay(idleDelay)
                        } else {
                            processAndAck(records)
                        }
                    } catch (throwable: CancellationException) {
                        throw throwable
                    } catch (throwable: Throwable) {
                        logger.log(Level.SEVERE, "Media thumbnail worker failed while processing records", throwable)
                        delay(errorDelay)
                    }
                }
            }
    }

    @Synchronized
    fun stop() {
        val runningJob = job ?: return
        job = null

        runBlocking {
            runningJob.cancelAndJoin()
        }
    }

    fun process(record: RedisStreamRecord) {
        val eventType = record.fields["event_type"].orEmpty()
        if (eventType != MediaEventTypes.UPLOAD_COMPLETED) {
            return
        }

        val event = parseUploadCompletedEvent(record, eventType)

        val work =
            transactionRunner.runInTransaction {
                val media = mediaRepository.findById(event.mediaId)
                when {
                    MediaProcessedEventsTable.exists(event.eventId) -> {
                        ThumbnailWork.Done
                    }

                    media == null -> {
                        ThumbnailWork.MarkProcessed(event.eventId, eventType)
                    }

                    media.status == MediaStatus.DELETED -> {
                        ThumbnailWork.MarkProcessed(event.eventId, eventType)
                    }

                    media.status != MediaStatus.READY -> {
                        ThumbnailWork.MarkProcessed(event.eventId, eventType)
                    }

                    media.objectKey != event.objectKey -> {
                        ThumbnailWork.MarkProcessed(event.eventId, eventType)
                    }

                    media.contentLengthBytes > maxOriginalBytes -> {
                        ThumbnailWork.FailThumbnail(
                            eventId = event.eventId,
                            eventType = eventType,
                            mediaId = event.mediaId,
                            reason = "Original object exceeds thumbnail input limit: ${media.contentLengthBytes} bytes",
                        )
                    }

                    else -> {
                        val thumbnail =
                            media.variants.firstOrNull { it.variant == MediaVariantKind.THUMBNAIL }
                                ?: return@runInTransaction ThumbnailWork.MarkProcessed(event.eventId, eventType)
                        if (thumbnail.status == MediaVariantStatus.READY || thumbnail.status == MediaVariantStatus.FAILED) {
                            ThumbnailWork.MarkProcessed(event.eventId, eventType)
                        } else {
                            ThumbnailWork.Generate(
                                eventId = event.eventId,
                                eventType = eventType,
                                mediaId = event.mediaId,
                                originalObjectKey = media.objectKey,
                                thumbnailObjectKey = thumbnail.objectKey,
                            )
                        }
                    }
                }
            }

        when (work) {
            ThumbnailWork.Done -> Unit
            is ThumbnailWork.MarkProcessed -> markProcessed(work.eventId, work.eventType)
            is ThumbnailWork.FailThumbnail -> markThumbnailFailed(work.mediaId, work.eventId, work.eventType, work.reason)
            is ThumbnailWork.Generate -> generateThumbnail(work)
        }
    }

    internal fun processAndAck(record: RedisStreamRecord) {
        try {
            process(record)
            consumerGroup.ack(record.id)
        } catch (e: MalformedMediaEventException) {
            logger.log(Level.WARNING, "Dropping malformed media event record ${record.id}: ${e.message}", e)
            if (e.eventId != null) {
                markProcessingFailed(e.eventId, e.eventType, e.message ?: "Malformed media event")
            }
            consumerGroup.ack(record.id)
        }
    }

    internal fun processAndAck(records: List<RedisStreamRecord>) {
        records.distinctBy { it.id }.forEach(::processAndAck)
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
                markThumbnailFailed(work.mediaId, work.eventId, work.eventType, e.message ?: "Original object too large")
                return
            }

        val thumbnail =
            try {
                thumbnailGenerator.generateJpeg(
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
            if (MediaProcessedEventsTable.exists(work.eventId)) {
                return@runInTransaction
            }

            val current = mediaRepository.findById(work.mediaId)
            if (current == null || current.status == MediaStatus.DELETED) {
                MediaProcessedEventsTable.tryMarkProcessed(work.eventId, work.eventType)
                return@runInTransaction
            }

            val currentThumbnail =
                current.variants.firstOrNull { it.variant == MediaVariantKind.THUMBNAIL }
                    ?: run {
                        MediaProcessedEventsTable.tryMarkProcessed(work.eventId, work.eventType)
                        return@runInTransaction
                    }

            if (currentThumbnail.status == MediaVariantStatus.READY) {
                MediaProcessedEventsTable.tryMarkProcessed(work.eventId, work.eventType)
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
            MediaProcessedEventsTable.tryMarkProcessed(work.eventId, work.eventType)
        }
    }

    private fun markThumbnailFailed(
        mediaId: MediaId,
        eventId: Uuid,
        eventType: String,
        reason: String,
    ) {
        transactionRunner.runInTransaction {
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
            MediaProcessedEventsTable.tryMarkProcessed(eventId, eventType)
        }
    }

    private fun markProcessingFailed(
        eventId: Uuid,
        eventType: String,
        error: String,
    ) {
        transactionRunner.runInTransaction {
            MediaProcessedEventsTable.tryMarkFailed(
                eventId = eventId,
                eventType = eventType,
                failedAt = clock.now(),
                lastError = error,
            )
        }
    }

    private fun parseUploadCompletedEvent(
        record: RedisStreamRecord,
        eventType: String,
    ): UploadCompletedEvent {
        val rawEventId =
            record.fields["event_id"]?.takeIf { it.isNotBlank() }
                ?: throw MalformedMediaEventException(null, eventType, "Redis stream record ${record.id} missing event_id")
        val eventId =
            try {
                Uuid.parse(rawEventId)
            } catch (e: IllegalArgumentException) {
                throw MalformedMediaEventException(null, eventType, "Redis stream record ${record.id} has invalid event_id", e)
            }
        val rawPayload =
            record.fields["payload"]?.takeIf { it.isNotBlank() }
                ?: throw MalformedMediaEventException(eventId, eventType, "Redis stream record ${record.id} missing payload")
        val payload =
            try {
                json.decodeFromString<UploadCompletedPayload>(rawPayload)
            } catch (e: SerializationException) {
                throw MalformedMediaEventException(eventId, eventType, "Redis stream record ${record.id} has invalid payload", e)
            } catch (e: IllegalArgumentException) {
                throw MalformedMediaEventException(eventId, eventType, "Redis stream record ${record.id} has invalid payload", e)
            }
        val mediaId =
            try {
                MediaId(parseGrpcUuid(payload.mediaId, fieldName = "media_id"))
            } catch (e: RuntimeException) {
                throw MalformedMediaEventException(eventId, eventType, "Redis stream record ${record.id} has invalid media_id", e)
            }
        if (payload.objectKey.isBlank()) {
            throw MalformedMediaEventException(eventId, eventType, "Redis stream record ${record.id} has blank object_key")
        }

        return UploadCompletedEvent(
            eventId = eventId,
            mediaId = mediaId,
            objectKey = payload.objectKey,
        )
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
        const val THUMBNAIL_CONTENT_TYPE = "image/jpeg"

        val json = Json { ignoreUnknownKeys = true }
    }
}

interface MediaThumbnailEventConsumer {
    fun ensureGroup(startId: String = "0")

    fun read(
        count: Long = 10,
        block: Duration = Duration.ofSeconds(1),
    ): List<RedisStreamRecord>

    fun ack(messageId: String): Long

    fun claimStale(
        minIdle: Duration,
        count: Long = 10,
    ): List<RedisStreamRecord>
}

class RedisMediaThumbnailEventConsumer(
    private val consumerGroup: RedisStreamConsumerGroup,
) : MediaThumbnailEventConsumer {
    override fun ensureGroup(startId: String) {
        consumerGroup.ensureGroup(startId)
    }

    override fun read(
        count: Long,
        block: Duration,
    ): List<RedisStreamRecord> = consumerGroup.read(count = count, block = block)

    override fun ack(messageId: String): Long = consumerGroup.ack(messageId)

    override fun claimStale(
        minIdle: Duration,
        count: Long,
    ): List<RedisStreamRecord> = consumerGroup.claimStale(minIdle = minIdle, count = count)
}

fun interface ThumbnailGenerator {
    fun generateJpeg(
        sourceBytes: ByteArray,
        maxSizePx: Int,
    ): GeneratedThumbnail
}

data class GeneratedThumbnail(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GeneratedThumbnail

        if (width != other.width) return false
        if (height != other.height) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

class UnsupportedImageException(
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ImageIoThumbnailGenerator(
    private val maxSourcePixels: Long = 24_000_000,
) : ThumbnailGenerator {
    init {
        require(maxSourcePixels > 0) { "maxSourcePixels must be positive" }
    }

    override fun generateJpeg(
        sourceBytes: ByteArray,
        maxSizePx: Int,
    ): GeneratedThumbnail {
        val source =
            try {
                decodeBounded(sourceBytes)
            } catch (e: UnsupportedImageException) {
                throw e
            } catch (e: Exception) {
                throw UnsupportedImageException("Unable to decode image", e)
            }

        val scale = min(1.0, maxSizePx.toDouble() / maxOf(source.width, source.height).toDouble())
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val thumbnail = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = thumbnail.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }

        val output = ByteArrayOutputStream()
        try {
            if (!ImageIO.write(thumbnail, "jpg", output)) {
                throw UnsupportedImageException()
            }
        } catch (e: UnsupportedImageException) {
            throw e
        } catch (e: Exception) {
            throw UnsupportedImageException("Unable to encode thumbnail", e)
        }

        return GeneratedThumbnail(
            bytes = output.toByteArray(),
            width = width,
            height = height,
        )
    }

    private fun decodeBounded(sourceBytes: ByteArray): BufferedImage {
        ImageIO.createImageInputStream(ByteArrayInputStream(sourceBytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) {
                throw UnsupportedImageException("Unsupported image format")
            }

            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width.toLong() * height.toLong() > maxSourcePixels) {
                    throw UnsupportedImageException("Image dimensions exceed thumbnail input limit")
                }
                return reader.read(0) ?: throw UnsupportedImageException("Unable to decode image")
            } finally {
                reader.dispose()
                disposeRemainingReaders(readers)
            }
        }
    }

    private fun disposeRemainingReaders(readers: Iterator<ImageReader>) {
        while (readers.hasNext()) {
            readers.next().dispose()
        }
    }
}

object MediaProcessedEventsTable : ProcessedEventsTable("media_processed_events") {
    val failedAt = timestamp("failed_at").nullable()
    val lastError = varchar("last_error", length = 512).nullable()
}

private fun MediaProcessedEventsTable.tryMarkFailed(
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
        update({ this.eventId eq eventId }) {
            it[this.eventType] = eventType
            it[processedAt] = failedAt.toJavaInstant()
            it[this.failedAt] = failedAt.toJavaInstant()
            it[this.lastError] = lastError.take(512)
        }
    }
}

private data class UploadCompletedEvent(
    val eventId: Uuid,
    val mediaId: MediaId,
    val objectKey: String,
)

@Serializable
private data class UploadCompletedPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("object_key") val objectKey: String,
)

private class MalformedMediaEventException(
    val eventId: Uuid?,
    val eventType: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
