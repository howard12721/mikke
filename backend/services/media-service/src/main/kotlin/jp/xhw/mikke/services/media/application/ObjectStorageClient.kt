package jp.xhw.mikke.services.media.application

import kotlin.time.Duration
import kotlin.time.Instant

data class PresignedUpload(
    val uploadUrl: String,
    val requiredHeaders: Map<String, String>,
    val expiresAt: Instant,
)

data class PresignedDownload(
    val url: String,
    val expiresAt: Instant,
)

data class StoredObjectMetadata(
    val contentLengthBytes: Long,
    val contentType: String,
    val etag: String,
)

data class StoredObject(
    val bytes: ByteArray,
    val metadata: StoredObjectMetadata,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StoredObject

        if (!bytes.contentEquals(other.bytes)) return false
        if (metadata != other.metadata) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

class ObjectTooLargeException(
    objectKey: String,
    contentLengthBytes: Long,
    maxContentLengthBytes: Long,
) : RuntimeException("Object $objectKey is too large: $contentLengthBytes bytes exceeds $maxContentLengthBytes bytes")

interface ObjectStorageClient {
    fun createPresignedPutUrl(
        objectKey: String,
        contentType: String,
        contentLengthBytes: Long,
        expiresIn: Duration,
    ): PresignedUpload

    fun createPresignedGetUrl(
        objectKey: String,
        expiresIn: Duration,
    ): PresignedDownload

    fun headObject(objectKey: String): StoredObjectMetadata?

    fun getObject(
        objectKey: String,
        maxContentLengthBytes: Long? = null,
    ): StoredObject?

    fun putObject(
        objectKey: String,
        contentType: String,
        bytes: ByteArray,
    ): StoredObjectMetadata
}
