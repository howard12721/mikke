package jp.xhw.mikke.api.media.application

import jp.xhw.mikke.api.common.application.requireText
import jp.xhw.mikke.api.graphql.ApiRequestContext

class MediaApiService(
    private val mediaGateway: MediaGateway,
) {
    suspend fun createUploadUrl(
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
        )

    suspend fun checkUpload(
        context: ApiRequestContext,
        mediaId: String,
        objectKey: String,
    ): UploadCheck =
        mediaGateway.checkUpload(
            context = context,
            mediaId = mediaId.requireText("mediaId"),
            objectKey = objectKey.requireText("objectKey"),
        )
}
