package jp.xhw.mikke.events.post

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PostDeletedPayload(
    @SerialName("post_id") val postId: String,
    @SerialName("author_user_id") val authorUserId: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("deleted_at") val deletedAt: String,
)
