package jp.xhw.mikke.api.media.application

import jp.xhw.mikke.api.common.application.requireText
import jp.xhw.mikke.api.common.application.requireUuidText
import jp.xhw.mikke.api.graphql.ApiRequestContext

class MediaApiService(
    private val mediaGateway: MediaGateway,
) {
    suspend fun createAvatarUploadUrl(
        context: ApiRequestContext,
        contentType: String,
        contentLengthBytes: Long,
        originalFileName: String?,
    ): MediaUploadUrl =
        mediaGateway.createUploadUrl(
            context = context,
            contentType = contentType.requireText("contentType"),
            contentLengthBytes = contentLengthBytes,
            originalFileName = originalFileName?.trim()?.takeIf { it.isNotEmpty() },
            generatedVariant = GeneratedVariant.ICON,
        )

    suspend fun createPostPhotoUploadUrl(
        context: ApiRequestContext,
        contentType: String,
        contentLengthBytes: Long,
        originalFileName: String?,
    ): MediaUploadUrl =
        mediaGateway.createUploadUrl(
            context = context,
            contentType = contentType.requireText("contentType"),
            contentLengthBytes = contentLengthBytes,
            originalFileName = originalFileName?.trim()?.takeIf { it.isNotEmpty() },
            generatedVariant = GeneratedVariant.THUMBNAIL,
        )

    suspend fun checkUpload(
        context: ApiRequestContext,
        mediaId: String,
        objectKey: String,
    ): UploadCheck =
        mediaGateway.checkUpload(
            context = context,
            mediaId = mediaId.requireUuidText("mediaId"),
            objectKey = objectKey.requireText("objectKey"),
        )
}
