package jp.xhw.mikke.services.media.worker

import jp.xhw.mikke.events.core.EventEnvelope
import jp.xhw.mikke.events.media.MediaEventTypes
import jp.xhw.mikke.events.media.MediaUploadCompletedPayload
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.events.ProcessedEventStore
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
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
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MediaUploadCompletedHandlerTest {
    @Test
    fun `missing original marks thumbnail failed and records diagnostics`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000101")
            val media = mediaRecord()
            val repository = InMemoryMediaRepository(media)
            val handler =
                createHandler(
                    database = database,
                    repository = repository,
                    objectStorage = FakeObjectStorageClient(),
                )

            runBlocking {
                handler.handle(event(eventId = eventId, media = media))
            }

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
            val handler =
                createHandler(
                    database = database,
                    repository = repository,
                    objectStorage = objectStorage,
                    maxOriginalBytes = 10,
                )

            runBlocking {
                handler.handle(event(eventId = eventId, media = media))
            }

            assertEquals(0, objectStorage.getObjectCalls)
            assertEquals(MediaVariantStatus.FAILED, repository.thumbnailStatus(media.id))
            assertTrue(processedRow(database, eventId).lastError.orEmpty().contains("exceeds thumbnail input limit"))
        }

    @Test
    fun `unsupported image preserves diagnostic`() =
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
            val handler =
                createHandler(
                    database = database,
                    repository = repository,
                    objectStorage = objectStorage,
                    thumbnailGenerator = ThumbnailGenerator { _, _ -> throw UnsupportedImageException("pixel-limit breach") },
                )

            runBlocking {
                handler.handle(event(eventId = eventId, media = media))
            }

            assertEquals(MediaVariantStatus.FAILED, repository.thumbnailStatus(media.id))
            assertEquals("pixel-limit breach", processedRow(database, eventId).lastError)
        }

    @Test
    fun `tryMarkFailed does not overwrite successful processed event`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000106")
            val eventType = MediaEventTypes.UPLOAD_COMPLETED

            transaction(database) {
                ProcessedEventStore(MediaProcessedEventsTable).tryMarkProcessed(eventId, eventType)
            }

            transaction(database) {
                MediaProcessedEventsTable.tryMarkFailed(
                    eventId = eventId,
                    eventType = eventType,
                    failedAt = NOW,
                    lastError = "late failure",
                )
            }

            val row = processedRow(database, eventId)
            assertEquals(null, row.failedAt)
            assertEquals(null, row.lastError)
        }

    @Test
    fun `duplicate event id does not regenerate thumbnail and keeps latest failure diagnostics`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000105")
            val media = mediaRecord()
            val repository = InMemoryMediaRepository(media)
            val objectStorage = FakeObjectStorageClient()
            val handler =
                createHandler(
                    database = database,
                    repository = repository,
                    objectStorage = objectStorage,
                )

            runBlocking {
                handler.handle(event(eventId = eventId, media = media))
                handler.handle(event(eventId = eventId, media = media))
            }

            assertEquals(1, objectStorage.getObjectCalls)
            assertTrue(processedRow(database, eventId).lastError.orEmpty().contains(media.objectKey))
        }

    @Test
    fun `successful thumbnail generation writes outbox once`() =
        withProcessedEventsTable { database ->
            val eventId = Uuid.parse("00000000-0000-4000-8000-000000000104")
            val media = mediaRecord()
            val repository = InMemoryMediaRepository(media)
            val objectStorage = successfulObjectStorage(media)
            val outbox = RecordingMediaOutbox()
            val handler =
                createHandler(
                    database = database,
                    repository = repository,
                    objectStorage = objectStorage,
                    mediaOutbox = outbox,
                    thumbnailGenerator = { _, _ -> GeneratedThumbnail(byteArrayOf(9), width = 4, height = 4) },
                )

            runBlocking {
                handler.handle(event(eventId = eventId, media = media))
            }

            assertEquals(1, objectStorage.putObjectCalls)
            assertEquals(1, outbox.thumbnailReadyCount)
        }

    private fun createHandler(
        database: Database,
        repository: InMemoryMediaRepository,
        objectStorage: FakeObjectStorageClient,
        mediaOutbox: RecordingMediaOutbox = RecordingMediaOutbox(),
        thumbnailGenerator: ThumbnailGenerator = ThumbnailGenerator { _, _ -> GeneratedThumbnail(byteArrayOf(9), width = 4, height = 4) },
        maxOriginalBytes: Long = 1_024,
    ): MediaUploadCompletedHandler =
        MediaUploadCompletedHandler(
            mediaRepository = repository,
            mediaOutbox = mediaOutbox,
            objectStorageClient = objectStorage,
            transactionRunner = ExposedTestTransactionRunner(database),
            processedEventStore = ProcessedEventStore(MediaProcessedEventsTable),
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

    private fun event(
        eventId: Uuid,
        media: MediaRecord,
    ): EventEnvelope<MediaUploadCompletedPayload> =
        EventEnvelope(
            eventId = eventId.toString(),
            eventType = MediaEventTypes.UPLOAD_COMPLETED,
            eventVersion = 1,
            occurredAt = NOW.toString(),
            producer = "media-service",
            aggregateType = "media",
            aggregateId = formatGrpcUuid(media.id.value),
            payload =
                MediaUploadCompletedPayload(
                    mediaId = formatGrpcUuid(media.id.value),
                    uploaderUserId = formatGrpcUuid(media.uploaderUserId.value),
                    objectKey = media.objectKey,
                    contentType = media.contentType,
                    contentLengthBytes = media.contentLengthBytes,
                    etag = media.etag.orEmpty(),
                    uploadedAt = media.uploadedAt?.toString().orEmpty(),
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
