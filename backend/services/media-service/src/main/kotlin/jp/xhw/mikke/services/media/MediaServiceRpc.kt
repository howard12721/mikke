package jp.xhw.mikke.services.media

import io.grpc.Status
import jp.xhw.mikke.media.v1.*
import jp.xhw.mikke.platform.grpc.currentAuthenticatedUser
import jp.xhw.mikke.platform.grpc.withGrpcExceptionMapping
import jp.xhw.mikke.platform.time.toProtoTimestamp
import jp.xhw.mikke.services.media.application.*
import jp.xhw.mikke.services.media.model.UploaderUserId
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("media-service")

class MediaServiceRpc(
    private val mediaService: MediaService,
) : MediaServiceGrpcKt.MediaServiceCoroutineImplBase() {
    override suspend fun createUploadUrl(request: CreateUploadUrlRequest): CreateUploadUrlResponse {
        val uploaderUserId = UploaderUserId(currentAuthenticatedUser())
        val result =
            mapRpcExceptions {
                mediaService.createUploadUrl(
                    CreateUploadUrlCommand(
                        contentType = request.contentType.requireField("content_type"),
                        contentLengthBytes = request.contentLengthBytes.requirePositive("content_length_bytes"),
                        originalFileName = request.originalFileName.takeIf { it.isNotBlank() },
                        uploaderUserId = uploaderUserId,
                    ),
                )
            }

        return CreateUploadUrlResponse
            .newBuilder()
            .setMediaId(formatMediaId(result.mediaId))
            .setObjectKey(result.objectKey)
            .setUploadUrl(result.uploadUrl)
            .setUploadMethod(UploadMethod.UPLOAD_METHOD_PUT)
            .putAllRequiredHeaders(result.requiredHeaders)
            .setExpiresAt(result.expiresAt.toProtoTimestamp())
            .build()
    }

    override suspend fun checkUpload(request: CheckUploadRequest): CheckUploadResponse {
        val requesterUserId = UploaderUserId(currentAuthenticatedUser())
        val result =
            mapRpcExceptions {
                mediaService.checkUpload(
                    CheckUploadCommand(
                        mediaId = parseMediaId(request.mediaId.requireField("media_id")),
                        objectKey = request.objectKey.requireField("object_key"),
                    ),
                    requesterUserId = requesterUserId,
                )
            }

        return CheckUploadResponse
            .newBuilder()
            .setMediaId(formatMediaId(result.mediaId))
            .setObjectKey(result.objectKey)
            .setStatus(result.status)
            .setContentLengthBytes(result.contentLengthBytes)
            .setContentType(result.contentType)
            .setEtag(result.etag)
            .build()
    }

    override suspend fun getMedia(request: GetMediaRequest): GetMediaResponse {
        val media =
            mapRpcExceptions {
                mediaService.getMedia(parseMediaId(request.mediaId.requireField("media_id")))
            }

        return GetMediaResponse
            .newBuilder()
            .setMedia(media.toProto())
            .build()
    }

    override suspend fun batchGetMedia(request: BatchGetMediaRequest): BatchGetMediaResponse {
        val mediaList =
            mapRpcExceptions {
                mediaService.batchGetMedia(
                    request.mediaIdsList.map { parseMediaId(it.requireField("media_id")) },
                )
            }

        return BatchGetMediaResponse
            .newBuilder()
            .addAllMedia(mediaList.map { it.toProto() })
            .build()
    }

    override suspend fun deleteMedia(request: DeleteMediaRequest): DeleteMediaResponse {
        val requesterUserId = UploaderUserId(currentAuthenticatedUser())
        mapRpcExceptions {
            mediaService.deleteMedia(
                mediaId = parseMediaId(request.mediaId.requireField("media_id")),
                requesterUserId = requesterUserId,
            )
        }

        return DeleteMediaResponse.getDefaultInstance()
    }
}

private fun String.requireField(fieldName: String): String =
    trim().takeIf { it.isNotEmpty() }
        ?: throw Status.INVALID_ARGUMENT.withDescription("$fieldName is required").asRuntimeException()

private fun Long.requirePositive(fieldName: String): Long {
    if (this <= 0) {
        throw Status.INVALID_ARGUMENT.withDescription("$fieldName must be positive").asRuntimeException()
    }
    return this
}

private suspend inline fun <T> mapRpcExceptions(crossinline block: () -> T): T =
    withGrpcExceptionMapping(
        logger = logger,
        serviceName = "media-service",
        internalErrorDescription = "Internal media service error",
        domainExceptionMapper = { candidate ->
            (candidate as? MediaApplicationException)?.toGrpcStatus()
        },
    ) {
        block()
    }

private fun formatMediaId(mediaId: jp.xhw.mikke.services.media.model.MediaId): String =
    jp.xhw.mikke.platform.uuid
        .formatGrpcUuid(mediaId.value)
