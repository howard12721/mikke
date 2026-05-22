package jp.xhw.mikke.services.post.application

import jp.xhw.mikke.services.post.model.MediaId
import jp.xhw.mikke.services.post.model.UserId

data class VerifiedPostMedia(
    val mediaId: MediaId,
    val contentType: String,
)

interface PostMediaChecker {
    suspend fun verifyReadyMediaOwnedBy(
        mediaId: MediaId,
        ownerUserId: UserId,
    ): VerifiedPostMedia
}
