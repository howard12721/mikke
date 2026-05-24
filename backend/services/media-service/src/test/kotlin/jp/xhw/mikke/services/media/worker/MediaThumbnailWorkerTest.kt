package jp.xhw.mikke.services.media.worker

import jp.xhw.mikke.events.media.MediaEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.redis.RedisStreamRecord
import jp.xhw.mikke.services.media.application.MediaRepository
import jp.xhw.mikke.services.media.application.ObjectStorageClient
import jp.xhw.mikke.services.media.application.ObjectTooLargeException
import jp.xhw.mikke.services.media.application.PresignedDownload
import jp.xhw.mikke.services.media.application.PresignedUpload
import jp.xhw.mikke.services.media.application.StoredObject
import jp.xhw.mikke.services.media.application.StoredObjectMetadata
import jp.xhw.mikke.services.media.application.port.MediaOutbox
import jp.xhw.mikke.services.media.model.MediaId
import jp.xhw.mikke.services.media.model.MediaRecord
import jp.xhw.mikke.services.media.model.MediaStatus
import jp.xhw.mikke.services.media.model.MediaVariantId
import jp.xhw.mikke.services.media.model.MediaVariantKind
import jp.xhw.mikke.services.media.model.MediaVariantRecord
import jp.xhw.mikke.services.media.model.MediaVariantStatus
import jp.xhw.mikke.services.media.model.UploadMethod
import jp.xhw.mikke.services.media.model.UploaderUserId
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MediaThumbnailWorkerTest {
    @Test
    fun `missing original marks thumbnail failed, records diagnostics, and acks`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000101")
            val media = mediaRecord()
            val repository = InMemoryMediaRepository(media)
            val consumer = FakeMediaThumbnailEventConsumer()
            val worker =
                createWorker(
                    database = database,
                    repository = repository,
                    consumer = consumer,
                    objectStorage = FakeObjectStorageClient(),
                )

            worker.processAndAck(record(eventId = eventId, media = media))

            assertEquals(listOf("1-0"), consumer.ackedIds)
            assertEquals(MediaVariantStatus.FAILED, repository.thumbnailStatus(media.id))
            val processed = processedRow(database, eventId)
            assertNotNull(processed.failedAt)
            assertTrue(processed.lastError.orEmpty().contains(media.objectKey))
        }

    @Test
    fun `oversized original is terminal and does not fetch object`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000102")
            val media = mediaRecord(contentLengthBytes = 20)
            val repository = InMemoryMediaRepository(media)
            val objectStorage = FakeObjectStorageClient()
            val consumer = FakeMediaThumbnailEventConsumer()
            val worker =
                createWorker(
                    database = database,
                    repository = repository,
                    consumer = consumer,
                    objectStorage = objectStorage,
                    maxOriginalBytes = 10,
                )

            worker.processAndAck(record(eventId = eventId, media = media))

            assertEquals(0, objectStorage.getObjectCalls)
            assertEquals(listOf("1-0"), consumer.ackedIds)
            assertEquals(MediaVariantStatus.FAILED, repository.thumbnailStatus(media.id))
            assertTrue(processedRow(database, eventId).lastError.orEmpty().contains("exceeds thumbnail input limit"))
        }

    @Test
    fun `unsupported image preserves diagnostic and acks`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000103")
            val media = mediaRecord()
            val repository = InMemoryMediaRepository(media)
            val objectStorage =
                FakeObjectStorageClient(
                    objects =
                        mutableMapOf(
                            media.objectKey to
                                StoredObject(
                                    bytes = byteArrayOf(1, 2, 3),
                                    metadata = StoredObjectMetadata(3, "image/png", "etag"),
                                ),
                        ),
                )
            val consumer = FakeMediaThumbnailEventConsumer()
            val worker =
                createWorker(
                    database = database,
                    repository = repository,
                    consumer = consumer,
                    objectStorage = objectStorage,
                    thumbnailGenerator = ThumbnailGenerator { _, _ -> throw UnsupportedImageException("pixel-limit breach") },
                )

            worker.processAndAck(record(eventId = eventId, media = media))

            assertEquals(listOf("1-0"), consumer.ackedIds)
            assertEquals(MediaVariantStatus.FAILED, repository.thumbnailStatus(media.id))
            assertEquals("pixel-limit breach", processedRow(database, eventId).lastError)
        }

    @Test
    fun `duplicate records in one batch are processed and acked once`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000104")
            val media = mediaRecord()
            val repository = InMemoryMediaRepository(media)
            val objectStorage = successfulObjectStorage(media)
            val outbox = RecordingMediaOutbox()
            val consumer = FakeMediaThumbnailEventConsumer()
            val worker =
                createWorker(
                    database = database,
                    repository = repository,
                    consumer = consumer,
                    objectStorage = objectStorage,
                    mediaOutbox = outbox,
                    thumbnailGenerator = { _, _ -> GeneratedThumbnail(byteArrayOf(9), width = 4, height = 4) },
                )
            val record = record(eventId = eventId, media = media)

            worker.processAndAck(listOf(record, record))

            assertEquals(listOf("1-0"), consumer.ackedIds)
            assertEquals(1, objectStorage.putObjectCalls)
            assertEquals(1, outbox.thumbnailReadyCount)
        }

    @Test
    fun `duplicate event id does not regenerate thumbnail and keeps latest failure diagnostics`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000105")
            val media = mediaRecord()
            val repository = InMemoryMediaRepository(media)
            val objectStorage = FakeObjectStorageClient()
            val consumer = FakeMediaThumbnailEventConsumer()
            val worker =
                createWorker(
                    database = database,
                    repository = repository,
                    consumer = consumer,
                    objectStorage = objectStorage,
                )

            worker.processAndAck(record(id = "1-0", eventId = eventId, media = media))
            worker.processAndAck(record(id = "2-0", eventId = eventId, media = media))

            assertEquals(listOf("1-0", "2-0"), consumer.ackedIds)
            assertEquals(1, objectStorage.getObjectCalls)
            assertTrue(processedRow(database, eventId).lastError.orEmpty().contains(media.objectKey))
        }

    private fun createWorker(
        database: Database,
        repository: InMemoryMediaRepository,
        consumer: FakeMediaThumbnailEventConsumer,
        objectStorage: FakeObjectStorageClient,
        mediaOutbox: RecordingMediaOutbox = RecordingMediaOutbox(),
        thumbnailGenerator: ThumbnailGenerator = ThumbnailGenerator { _, _ -> GeneratedThumbnail(byteArrayOf(9), width = 4, height = 4) },
        maxOriginalBytes: Long = 1_024,
    ): MediaThumbnailWorker =
        MediaThumbnailWorker(
            consumerGroup = consumer,
            mediaRepository = repository,
            mediaOutbox = mediaOutbox,
            objectStorageClient = objectStorage,
            transactionRunner = ExposedTestTransactionRunner(database),
            thumbnailGenerator = thumbnailGenerator,
            maxOriginalBytes = maxOriginalBytes,
            clock = FixedClock(NOW),
        )

    private fun successfulObjectStorage(media: MediaRecord): FakeObjectStorageClient =
        FakeObjectStorageClient(
            objects =
                mutableMapOf(
                    media.objectKey to
                        StoredObject(
                            bytes = byteArrayOf(1, 2, 3),
                            metadata = StoredObjectMetadata(3, "image/png", "etag"),
                        ),
                ),
        )

    private fun record(
        id: String = "1-0",
        eventId: Uuid,
        media: MediaRecord,
    ): RedisStreamRecord =
        RedisStreamRecord(
            id = id,
            fields =
                mapOf(
                    "event_id" to eventId.toString(),
                    "event_type" to MediaEventTypes.UPLOAD_COMPLETED,
                    "payload" to """{"media_id":"${media.id.value}","object_key":"${media.objectKey}"}""",
                ),
        )

    private fun mediaRecord(contentLengthBytes: Long = 3): MediaRecord {
        val mediaId = MediaId(Uuid.random())
        val originalObjectKey = "media/original/${Uuid.random()}"
        return MediaRecord(
            id = mediaId,
            uploaderUserId = UploaderUserId(Uuid.random()),
            objectKey = originalObjectKey,
            contentType = "image/png",
            contentLengthBytes = contentLengthBytes,
            etag = "etag",
            status = MediaStatus.READY,
            uploadMethod = UploadMethod.PUT,
            createdAt = NOW,
            uploadedAt = NOW,
            deletedAt = null,
            variants =
                listOf(
                    MediaVariantRecord(
                        id = MediaVariantId(Uuid.random()),
                        mediaId = mediaId,
                        variant = MediaVariantKind.ORIGINAL,
                        objectKey = originalObjectKey,
                        status = MediaVariantStatus.READY,
                        width = null,
                        height = null,
                        contentType = "image/png",
                        contentLengthBytes = contentLengthBytes,
                        createdAt = NOW,
                        readyAt = NOW,
                    ),
                    MediaVariantRecord(
                        id = MediaVariantId(Uuid.random()),
                        mediaId = mediaId,
                        variant = MediaVariantKind.THUMBNAIL,
                        objectKey = "media/thumbnail/${Uuid.random()}",
                        status = MediaVariantStatus.PENDING,
                        width = null,
                        height = null,
                        contentType = "image/png",
                        contentLengthBytes = null,
                        createdAt = NOW,
                        readyAt = null,
                    ),
                ),
        )
    }

    private fun withProcessedEventsTable(block: (Database) -> Unit) {
        val database =
            Database.connect(
                url = "jdbc:h2:mem:${Uuid.random()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        transaction(database) {
            SchemaUtils.create(MediaProcessedEventsTable)
        }
        block(database)
    }

    private fun processedRow(
        database: Database,
        eventId: Uuid,
    ): ProcessedFailureRow =
        transaction(database) {
            val row =
                MediaProcessedEventsTable
                    .selectAll()
                    .where { MediaProcessedEventsTable.eventId eq eventId }
                    .single()
            ProcessedFailureRow(
                failedAt = row[MediaProcessedEventsTable.failedAt],
                lastError = row[MediaProcessedEventsTable.lastError],
            )
        }

    private companion object {
        val NOW = Instant.parse("2026-05-24T01:02:03Z")
    }
}

private data class ProcessedFailureRow(
    val failedAt: java.time.Instant?,
    val lastError: String?,
)

private class FakeMediaThumbnailEventConsumer : MediaThumbnailEventConsumer {
    val ackedIds = mutableListOf<String>()

    override fun ensureGroup(startId: String) = Unit

    override fun read(
        count: Long,
        block: Duration,
    ): List<RedisStreamRecord> = emptyList()

    override fun ack(messageId: String): Long {
        ackedIds += messageId
        return 1
    }

    override fun claimStale(
        minIdle: Duration,
        count: Long,
    ): List<RedisStreamRecord> = emptyList()
}

private class InMemoryMediaRepository(
    media: MediaRecord,
) : MediaRepository {
    private val mediaById = linkedMapOf(media.id to media)

    override fun insert(media: MediaRecord) {
        mediaById[media.id] = media
    }

    override fun findById(id: MediaId): MediaRecord? = mediaById[id]

    override fun findByIds(ids: List<MediaId>): List<MediaRecord> = ids.mapNotNull(mediaById::get)

    override fun update(media: MediaRecord) {
        mediaById[media.id] = media
    }

    override fun updateVariant(variant: MediaVariantRecord) {
        val media = mediaById[variant.mediaId] ?: return
        mediaById[variant.mediaId] =
            media.copy(
                variants =
                    media.variants.map {
                        if (it.variant == variant.variant) variant else it
                    },
            )
    }

    override fun findVariant(
        mediaId: MediaId,
        variant: MediaVariantKind,
    ): MediaVariantRecord? = mediaById[mediaId]?.variants?.firstOrNull { it.variant == variant }

    fun thumbnailStatus(mediaId: MediaId): MediaVariantStatus = checkNotNull(findVariant(mediaId, MediaVariantKind.THUMBNAIL)).status
}

private class RecordingMediaOutbox : MediaOutbox {
    var thumbnailReadyCount = 0

    override fun appendUploadUrlCreated(
        media: MediaRecord,
        expiresAt: Instant,
    ) = Unit

    override fun appendUploadCompleted(media: MediaRecord) = Unit

    override fun appendThumbnailReady(variant: MediaVariantRecord) {
        thumbnailReadyCount += 1
    }

    override fun appendDeleted(
        mediaId: MediaId,
        deletedAt: Instant,
    ) = Unit
}

private class FakeObjectStorageClient(
    private val objects: MutableMap<String, StoredObject> = mutableMapOf(),
) : ObjectStorageClient {
    var getObjectCalls = 0
    var putObjectCalls = 0

    override fun createPresignedPutUrl(
        objectKey: String,
        contentType: String,
        contentLengthBytes: Long,
        expiresIn: kotlin.time.Duration,
    ): PresignedUpload = error("not used")

    override fun createPresignedGetUrl(
        objectKey: String,
        expiresIn: kotlin.time.Duration,
    ): PresignedDownload = error("not used")

    override fun headObject(objectKey: String): StoredObjectMetadata? = error("not used")

    override fun getObject(
        objectKey: String,
        maxContentLengthBytes: Long?,
    ): StoredObject? {
        getObjectCalls += 1
        val stored = objects[objectKey] ?: return null
        if (maxContentLengthBytes != null && stored.bytes.size > maxContentLengthBytes) {
            throw ObjectTooLargeException(objectKey, stored.bytes.size.toLong(), maxContentLengthBytes)
        }
        return stored
    }

    override fun putObject(
        objectKey: String,
        contentType: String,
        bytes: ByteArray,
    ): StoredObjectMetadata {
        putObjectCalls += 1
        objects[objectKey] =
            StoredObject(
                bytes = bytes,
                metadata = StoredObjectMetadata(bytes.size.toLong(), contentType, "etag-$objectKey"),
            )
        return StoredObjectMetadata(bytes.size.toLong(), contentType, "etag-$objectKey")
    }
}

private class ExposedTestTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = transaction(database) { block() }
}

private class FixedClock(
    private val now: Instant,
) : Clock {
    override fun now(): Instant = now
}
