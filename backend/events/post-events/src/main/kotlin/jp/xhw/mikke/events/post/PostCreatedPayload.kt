package jp.xhw.mikke.events.post

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PostCreatedPayload(
    @SerialName("post_id") val postId: String,
    @SerialName("author_user_id") val authorUserId: String,
    @SerialName("media_id") val mediaId: String,
    val visibility: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)
