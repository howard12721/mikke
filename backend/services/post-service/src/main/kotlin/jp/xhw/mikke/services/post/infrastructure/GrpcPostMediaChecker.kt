package jp.xhw.mikke.services.post.infrastructure

import io.grpc.Status
import io.grpc.StatusException
import jp.xhw.mikke.media.v1.GetMediaRequest
import jp.xhw.mikke.media.v1.MediaServiceGrpcKt
import jp.xhw.mikke.media.v1.MediaStatus
import jp.xhw.mikke.services.post.application.MediaNotReadyException
import jp.xhw.mikke.services.post.application.MediaOwnershipException
import jp.xhw.mikke.services.post.application.PostMediaChecker
import jp.xhw.mikke.services.post.application.VerifiedPostMedia
import jp.xhw.mikke.services.post.model.MediaId
import jp.xhw.mikke.services.post.model.UserId

class GrpcPostMediaChecker(
    private val mediaService: MediaServiceGrpcKt.MediaServiceCoroutineStub,
) : PostMediaChecker {
    override suspend fun verifyReadyMediaOwnedBy(
        mediaId: MediaId,
        ownerUserId: UserId,
    ): VerifiedPostMedia {
        val media =
            try {
                mediaService
                    .getMedia(
                        GetMediaRequest
                            .newBuilder()
                            .setMediaId(mediaId.value.toString())
                            .build(),
                    ).media
            } catch (e: StatusException) {
                when (e.status.code) {
                    Status.Code.NOT_FOUND -> throw MediaNotReadyException("media not found")
                    else -> throw e
                }
            }

        if (media.uploaderUserId != ownerUserId.value.toString()) {
            throw MediaOwnershipException()
        }
        if (media.status != MediaStatus.MEDIA_STATUS_READY) {
            throw MediaNotReadyException()
        }

        return VerifiedPostMedia(
            mediaId = mediaId,
            contentType = media.contentType.trim().lowercase(),
        )
    }
}
