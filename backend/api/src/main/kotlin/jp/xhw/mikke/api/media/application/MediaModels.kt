package jp.xhw.mikke.api.media.application

import jp.xhw.mikke.api.graphql.ApiRequestContext

data class Media(
    val id: String,
    val objectKey: String,
    val originalUrl: String,
    val thumbnailUrl: String,
    val iconUrl: String?,
    val status: String,
    val contentType: String,
    val contentLengthBytes: Long,
    val etag: String,
    val uploaderUserId: String,
    val createdAt: String,
    val uploadedAt: String,
)

data class MediaUploadUrl(
    val mediaId: String,
    val objectKey: String,
    val uploadUrl: String,
    val uploadMethod: String,
    val requiredHeaders: Map<String, String>,
    val expiresAt: String,
)

data class UploadCheck(
    val mediaId: String,
    val objectKey: String,
    val status: String,
    val contentLengthBytes: Long,
    val contentType: String,
    val etag: String,
)

interface MediaGateway : AutoCloseable {
    suspend fun createUploadUrl(
        context: ApiRequestContext,
        contentType: String,
        contentLengthBytes: Long,
        originalFileName: String?,
        generatedVariant: GeneratedVariant,
    ): MediaUploadUrl

    suspend fun checkUpload(
        context: ApiRequestContext,
        mediaId: String,
        objectKey: String,
    ): UploadCheck

    suspend fun getMedia(
        context: ApiRequestContext,
        mediaId: String,
    ): Media

    suspend fun batchGetMedia(
        context: ApiRequestContext,
        mediaIds: List<String>,
    ): List<Media>
}

enum class GeneratedVariant {
    THUMBNAIL,
    ICON,
}
