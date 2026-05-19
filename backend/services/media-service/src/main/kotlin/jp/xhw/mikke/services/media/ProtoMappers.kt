package jp.xhw.mikke.services.media

import jp.xhw.mikke.media.v1.GetMediaForDeliveryResponse
import jp.xhw.mikke.platform.time.toProtoTimestamp
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.media.application.MediaDeliveryResolution
import jp.xhw.mikke.services.media.application.MediaView
import jp.xhw.mikke.services.media.model.MediaStatus
import jp.xhw.mikke.services.media.model.MediaVariantKind
import jp.xhw.mikke.media.v1.Media as MediaProto
import jp.xhw.mikke.media.v1.MediaStatus as MediaStatusProto
import jp.xhw.mikke.media.v1.MediaVariant as MediaVariantProto

fun MediaView.toProto(): MediaProto =
    MediaProto
        .newBuilder()
        .setId(formatGrpcUuid(record.id.value))
        .setObjectKey(record.objectKey)
        .setOriginalUrl(deliveryUrls.originalUrl)
        .setThumbnailUrl(deliveryUrls.thumbnailUrl)
        .setStatus(record.status.toProto())
        .setContentType(record.contentType)
        .setContentLengthBytes(record.contentLengthBytes)
        .apply {
            record.etag?.let { setEtag(it) }
        }.setUploaderUserId(formatGrpcUuid(record.uploaderUserId.value))
        .setCreatedAt(record.createdAt.toProtoTimestamp())
        .apply {
            record.uploadedAt?.let { setUploadedAt(it.toProtoTimestamp()) }
        }.build()

fun MediaDeliveryResolution.toProto(): GetMediaForDeliveryResponse =
    GetMediaForDeliveryResponse
        .newBuilder()
        .setMediaId(formatGrpcUuid(mediaId.value))
        .setVariant(variant.toProto())
        .setObjectKey(objectKey)
        .setContentType(contentType)
        .build()

private fun MediaStatus.toProto(): MediaStatusProto =
    when (this) {
        MediaStatus.PENDING_UPLOAD -> MediaStatusProto.MEDIA_STATUS_PENDING_UPLOAD
        MediaStatus.READY -> MediaStatusProto.MEDIA_STATUS_READY
        MediaStatus.DELETED -> MediaStatusProto.MEDIA_STATUS_DELETED
        MediaStatus.FAILED -> MediaStatusProto.MEDIA_STATUS_FAILED
    }

private fun MediaVariantKind.toProto(): MediaVariantProto =
    when (this) {
        MediaVariantKind.ORIGINAL -> MediaVariantProto.MEDIA_VARIANT_ORIGINAL
        MediaVariantKind.THUMBNAIL -> MediaVariantProto.MEDIA_VARIANT_THUMBNAIL
    }
