package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.events.media.MediaEventTypes
import jp.xhw.mikke.media.v1.UploadStatus
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.services.media.application.port.MediaOutbox
import jp.xhw.mikke.services.media.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MediaServiceTest {
    @Test
    fun `createUploadUrl persists pending media and writes outbox`() {
        val repository = InMemoryMediaRepository()
        val outbox = RecordingMediaOutbox()
        val objectStorage = FakeObjectStorageClient()
        val service = createService(repository, outbox, objectStorage)
        val uploader = UploaderUserId(Uuid.parse("00000000-0000-4000-8000-000000000010"))

        val result =
            service.createUploadUrl(
                CreateUploadUrlCommand(
                    contentType = "image/jpeg",
                    contentLengthBytes = 2048,
                    originalFileName = "photo.jpg",
                    uploaderUserId = uploader,
                ),
            )

        assertNotNull(repository.savedMedia)
        assertEquals(MediaStatus.PENDING_UPLOAD, repository.savedMedia?.status)
        assertEquals(uploader, repository.savedMedia?.uploaderUserId)
        assertEquals(2, repository.savedMedia?.variants?.size)
        assertEquals("https://upload.example/put", result.uploadUrl)
        assertEquals(1, outbox.entries.size)
        assertEquals(MediaEventTypes.UPLOAD_URL_CREATED, outbox.entries.single().eventType)
    }

    @Test
    fun `checkUpload marks media ready when object storage matches`() {
        val repository = InMemoryMediaRepository()
        val outbox = RecordingMediaOutbox()
        val objectStorage = FakeObjectStorageClient()
        val service = createService(repository, outbox, objectStorage)
        val uploader = UploaderUserId(Uuid.parse("00000000-0000-4000-8000-000000000010"))

        val created =
            service.createUploadUrl(
                CreateUploadUrlCommand(
                    contentType = "image/png",
                    contentLengthBytes = 4096,
                    originalFileName = null,
                    uploaderUserId = uploader,
                ),
            )

        objectStorage.objects[created.objectKey] =
            StoredObjectMetadata(
                contentLengthBytes = 4096,
                contentType = "image/png",
                etag = "etag-1",
            )

        val checked =
            service.checkUpload(
                CheckUploadCommand(
                    mediaId = created.mediaId,
                    objectKey = created.objectKey,
                ),
                requesterUserId = uploader,
            )

        assertEquals(UploadStatus.UPLOAD_STATUS_UPLOADED, checked.status)
        assertEquals(MediaStatus.READY, repository.savedMedia?.status)
        assertEquals("etag-1", repository.savedMedia?.etag)
        assertEquals(MediaEventTypes.UPLOAD_COMPLETED, outbox.entries.last().eventType)
    }

    @Test
    fun `checkUpload returns pending when object is missing`() {
        val repository = InMemoryMediaRepository()
        val outbox = RecordingMediaOutbox()
        val objectStorage = FakeObjectStorageClient()
        val service = createService(repository, outbox, objectStorage)
        val uploader = UploaderUserId(Uuid.parse("00000000-0000-4000-8000-000000000010"))

        val created =
            service.createUploadUrl(
                CreateUploadUrlCommand(
                    contentType = "image/webp",
                    contentLengthBytes = 1024,
                    originalFileName = null,
                    uploaderUserId = uploader,
                ),
            )

        val checked =
            service.checkUpload(
                CheckUploadCommand(
                    mediaId = created.mediaId,
                    objectKey = created.objectKey,
                ),
                requesterUserId = uploader,
            )

        assertEquals(UploadStatus.UPLOAD_STATUS_PENDING, checked.status)
        assertEquals(MediaStatus.PENDING_UPLOAD, repository.savedMedia?.status)
        assertEquals(1, outbox.entries.size)
    }

    @Test
    fun `getMedia omits deleted media`() {
        val repository = InMemoryMediaRepository()
        val service = createService(repository, RecordingMediaOutbox(), FakeObjectStorageClient())
        val uploader = UploaderUserId(Uuid.random())
        val created =
            service.createUploadUrl(
                CreateUploadUrlCommand(
                    contentType = "image/jpeg",
                    contentLengthBytes = 100,
                    originalFileName = null,
                    uploaderUserId = uploader,
                ),
            )

        service.deleteMedia(created.mediaId, uploader)

        assertThrows(MediaNotFoundException::class.java) {
            service.getMedia(created.mediaId)
        }
    }

    @Test
    fun `batchGetMedia returns only existing non-deleted media`() {
        val repository = InMemoryMediaRepository()
        val service = createService(repository, RecordingMediaOutbox(), FakeObjectStorageClient())
        val uploader = UploaderUserId(Uuid.random())
        val first =
            service.createUploadUrl(
                CreateUploadUrlCommand(
                    contentType = "image/jpeg",
                    contentLengthBytes = 100,
                    originalFileName = null,
                    uploaderUserId = uploader,
                ),
            )
        val second =
            service.createUploadUrl(
                CreateUploadUrlCommand(
                    contentType = "image/jpeg",
                    contentLengthBytes = 200,
                    originalFileName = null,
                    uploaderUserId = uploader,
                ),
            )

        service.deleteMedia(second.mediaId, uploader)

        val results =
            service.batchGetMedia(
                listOf(
                    first.mediaId,
                    second.mediaId,
                    MediaId(Uuid.random()),
                ),
            )

        assertEquals(1, results.size)
        assertEquals(first.mediaId, results.single().record.id)
    }

    @Test
    fun `getMediaForDelivery resolves ready original variant`() {
        val repository = InMemoryMediaRepository()
        val outbox = RecordingMediaOutbox()
        val objectStorage = FakeObjectStorageClient()
        val service = createService(repository, outbox, objectStorage)
        val uploader = UploaderUserId(Uuid.random())
        val created =
            service.createUploadUrl(
                CreateUploadUrlCommand(
                    contentType = "image/jpeg",
                    contentLengthBytes = 100,
                    originalFileName = null,
                    uploaderUserId = uploader,
                ),
            )
        objectStorage.objects[created.objectKey] =
            StoredObjectMetadata(
                contentLengthBytes = 100,
                contentType = "image/jpeg",
                etag = "etag",
            )
        service.checkUpload(
            CheckUploadCommand(created.mediaId, created.objectKey),
            requesterUserId = uploader,
        )

        val originalDeliveryKey =
            repository.savedMedia
                ?.variants
                ?.first { it.variant == MediaVariantKind.ORIGINAL }
                ?.deliveryKey
                ?: error("missing original variant")

        val resolution = service.getMediaForDelivery(originalDeliveryKey)

        assertEquals(created.mediaId, resolution.mediaId)
        assertEquals(created.objectKey, resolution.objectKey)
    }

    @Test
    fun `getMediaForDelivery falls back to original object for pending thumbnail`() {
        val repository = InMemoryMediaRepository()
        val service = createService(repository, RecordingMediaOutbox(), FakeObjectStorageClient())
        val mediaId = MediaId(Uuid.random())
        val now = Instant.fromEpochSeconds(1)
        val originalObjectKey = "media/test/original"
        repository.savedMedia =
            MediaRecord(
                id = mediaId,
                uploaderUserId = UploaderUserId(Uuid.random()),
                objectKey = originalObjectKey,
                contentType = "image/jpeg",
                contentLengthBytes = 100,
                etag = "etag",
                status = MediaStatus.READY,
                uploadMethod = UploadMethod.PUT,
                createdAt = now,
                uploadedAt = now,
                deletedAt = null,
                variants =
                    listOf(
                        variant(mediaId, MediaVariantKind.ORIGINAL, "orig-key", originalObjectKey, MediaVariantStatus.READY, now),
                        variant(mediaId, MediaVariantKind.THUMBNAIL, "thumb-key", "media/test/thumbnail", MediaVariantStatus.PENDING, now),
                    ),
            )

        val resolution = service.getMediaForDelivery("thumb-key")

        assertEquals(MediaVariantKind.THUMBNAIL, resolution.variant)
        assertEquals(originalObjectKey, resolution.objectKey)
    }

    private fun variant(
        mediaId: MediaId,
        kind: MediaVariantKind,
        deliveryKey: String,
        objectKey: String,
        status: MediaVariantStatus,
        now: Instant,
    ) = MediaVariantRecord(
        id = MediaVariantId(Uuid.random()),
        mediaId = mediaId,
        variant = kind,
        deliveryKey = deliveryKey,
        objectKey = objectKey,
        status = status,
        width = null,
        height = null,
        contentType = "image/jpeg",
        contentLengthBytes = null,
        createdAt = now,
        readyAt = if (status == MediaVariantStatus.READY) now else null,
    )

    private fun createService(
        repository: InMemoryMediaRepository,
        outbox: RecordingMediaOutbox,
        objectStorage: FakeObjectStorageClient,
    ): MediaService =
        MediaService(
            mediaRepository = repository,
            mediaOutbox = outbox,
            objectStorageClient = objectStorage,
            deliveryUrlBuilder = MediaDeliveryUrlBuilder("https://media.mikke.pics"),
            transactionRunner = ImmediateTransactionRunner,
            clock = FixedClock(Instant.fromEpochSeconds(1_700_000_000, 0)),
        )
}

private class InMemoryMediaRepository : MediaRepository {
    private val mediaById = linkedMapOf<Uuid, MediaRecord>()

    var savedMedia: MediaRecord?
        get() = mediaById.values.lastOrNull()
        set(value) {
            if (value != null) {
                mediaById[value.id.value] = value
            }
        }

    override fun insert(media: MediaRecord) {
        mediaById[media.id.value] = media
    }

    override fun findById(id: MediaId): MediaRecord? = mediaById[id.value]

    override fun findByIds(ids: List<MediaId>): List<MediaRecord> = ids.mapNotNull { mediaById[it.value] }

    override fun findVariantByDeliveryKey(deliveryKey: String): MediaVariantRecord? =
        mediaById.values
            .asSequence()
            .flatMap { it.variants.asSequence() }
            .firstOrNull { it.deliveryKey == deliveryKey }

    override fun update(media: MediaRecord) {
        mediaById[media.id.value] = media
    }

    override fun findVariant(
        mediaId: MediaId,
        variant: MediaVariantKind,
    ): MediaVariantRecord? = mediaById[mediaId.value]?.variants?.firstOrNull { it.variant == variant }
}

private class RecordingMediaOutbox : MediaOutbox {
    val entries = mutableListOf<OutboxEntry>()

    override fun appendUploadUrlCreated(
        media: MediaRecord,
        expiresAt: Instant,
    ) {
        entries +=
            OutboxEntry(
                id = Uuid.random(),
                eventType = MediaEventTypes.UPLOAD_URL_CREATED,
                aggregateType = "media",
                aggregateId = media.id.value,
                payloadJson = "{}",
                createdAt = expiresAt,
            )
    }

    override fun appendUploadCompleted(media: MediaRecord) {
        entries +=
            OutboxEntry(
                id = Uuid.random(),
                eventType = MediaEventTypes.UPLOAD_COMPLETED,
                aggregateType = "media",
                aggregateId = media.id.value,
                payloadJson = "{}",
                createdAt = media.createdAt,
            )
    }

    override fun appendDeleted(
        mediaId: MediaId,
        deletedAt: Instant,
    ) {
        entries +=
            OutboxEntry(
                id = Uuid.random(),
                eventType = MediaEventTypes.DELETED,
                aggregateType = "media",
                aggregateId = mediaId.value,
                payloadJson = "{}",
                createdAt = deletedAt,
            )
    }
}

private class FakeObjectStorageClient : ObjectStorageClient {
    val objects = mutableMapOf<String, StoredObjectMetadata>()

    override fun createPresignedPutUrl(
        objectKey: String,
        contentType: String,
        contentLengthBytes: Long,
        expiresIn: kotlin.time.Duration,
    ): PresignedUpload =
        PresignedUpload(
            uploadUrl = "https://upload.example/put",
            requiredHeaders = mapOf("Content-Type" to contentType),
            expiresAt = Instant.fromEpochSeconds(1_700_000_900, 0),
        )

    override fun headObject(objectKey: String): StoredObjectMetadata? = objects[objectKey]
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private class FixedClock(
    private val now: Instant,
) : Clock {
    override fun now(): Instant = now
}
