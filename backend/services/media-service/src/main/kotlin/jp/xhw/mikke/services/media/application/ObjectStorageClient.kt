package jp.xhw.mikke.services.media.application

import kotlin.time.Duration
import kotlin.time.Instant

data class PresignedUpload(
    val uploadUrl: String,
    val requiredHeaders: Map<String, String>,
    val expiresAt: Instant,
)

data class StoredObjectMetadata(
    val contentLengthBytes: Long,
    val contentType: String,
    val etag: String,
)

interface ObjectStorageClient {
    fun createPresignedPutUrl(
        objectKey: String,
        contentType: String,
        contentLengthBytes: Long,
        expiresIn: Duration,
    ): PresignedUpload

    fun headObject(objectKey: String): StoredObjectMetadata?
}
