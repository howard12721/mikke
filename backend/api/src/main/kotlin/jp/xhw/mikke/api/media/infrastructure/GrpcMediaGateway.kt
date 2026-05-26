package jp.xhw.mikke.api.media.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.common.infrastructure.call
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.infrastructure.authHeaderInterceptor
import jp.xhw.mikke.api.infrastructure.closeChannel
import jp.xhw.mikke.api.infrastructure.gatewayChannelFromEnvironment
import jp.xhw.mikke.api.infrastructure.toIsoString
import jp.xhw.mikke.api.media.application.Media
import jp.xhw.mikke.api.media.application.MediaGateway
import jp.xhw.mikke.api.media.application.MediaUploadUrl
import jp.xhw.mikke.api.media.application.UploadCheck
import jp.xhw.mikke.media.v1.*
import jp.xhw.mikke.media.v1.Media as ProtoMedia

class GrpcMediaGateway(
    private val channel: ManagedChannel,
    private val stub: MediaServiceGrpcKt.MediaServiceCoroutineStub =
        MediaServiceGrpcKt.MediaServiceCoroutineStub(channel),
) : MediaGateway {
    override suspend fun createUploadUrl(
        context: ApiRequestContext,
        contentType: String,
        contentLengthBytes: Long,
        originalFileName: String?,
    ): MediaUploadUrl =
        call {
            val builder =
                CreateUploadUrlRequest
                    .newBuilder()
                    .setContentType(contentType)
                    .setContentLengthBytes(contentLengthBytes)
            originalFileName?.let(builder::setOriginalFileName)
            context.stub().createUploadUrl(builder.build()).toMediaUploadUrl()
        }

    override suspend fun checkUpload(
        context: ApiRequestContext,
        mediaId: String,
        objectKey: String,
    ): UploadCheck =
        call {
            context
                .stub()
                .checkUpload(
                    CheckUploadRequest
                        .newBuilder()
                        .setMediaId(mediaId)
                        .setObjectKey(objectKey)
                        .build(),
                ).toUploadCheck()
        }

    override suspend fun getMedia(
        context: ApiRequestContext,
        mediaId: String,
    ): Media =
        call {
            context
                .stub()
                .getMedia(GetMediaRequest.newBuilder().setMediaId(mediaId).build())
                .media
                .toMedia()
        }

    override suspend fun batchGetMedia(
        context: ApiRequestContext,
        mediaIds: List<String>,
    ): List<Media> =
        if (mediaIds.isEmpty()) {
            emptyList()
        } else {
            call {
                context
                    .stub()
                    .batchGetMedia(BatchGetMediaRequest.newBuilder().addAllMediaIds(mediaIds).build())
                    .mediaList
                    .map { it.toMedia() }
            }
        }

    override fun close() = closeChannel(channel)

    private fun ApiRequestContext.stub(): MediaServiceGrpcKt.MediaServiceCoroutineStub =
        authHeaderInterceptor(this)?.let { stub.withInterceptors(it) } ?: stub

    companion object {
        fun fromEnvironment(): GrpcMediaGateway =
            GrpcMediaGateway(
                gatewayChannelFromEnvironment(
                    targetEnv = "MEDIA_SERVICE_TARGET",
                    hostEnv = "MEDIA_SERVICE_HOST",
                    portEnv = "MEDIA_SERVICE_PORT",
                    defaultPort = 50052,
                ),
            )
    }
}

private fun CreateUploadUrlResponse.toMediaUploadUrl(): MediaUploadUrl =
    MediaUploadUrl(
        mediaId = mediaId,
        objectKey = objectKey,
        uploadUrl = uploadUrl,
        uploadMethod = uploadMethod.name.removePrefix("UPLOAD_METHOD_"),
        requiredHeaders = requiredHeadersMap,
        expiresAt = expiresAt.toIsoString(),
    )

private fun CheckUploadResponse.toUploadCheck(): UploadCheck =
    UploadCheck(
        mediaId = mediaId,
        objectKey = objectKey,
        status = status.name.removePrefix("UPLOAD_STATUS_"),
        contentLengthBytes = contentLengthBytes,
        contentType = contentType,
        etag = etag,
    )

private fun ProtoMedia.toMedia(): Media =
    Media(
        id = id,
        objectKey = objectKey,
        originalUrl = originalUrl,
        thumbnailUrl = thumbnailUrl,
        status = status.name.removePrefix("MEDIA_STATUS_"),
        contentType = contentType,
        contentLengthBytes = contentLengthBytes,
        etag = etag,
        uploaderUserId = uploaderUserId,
        createdAt = createdAt.toIsoString(),
        uploadedAt = uploadedAt.toIsoString(),
    )
