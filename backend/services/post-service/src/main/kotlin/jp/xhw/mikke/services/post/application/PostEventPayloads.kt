package jp.xhw.mikke.services.post.application

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PostCreatedPayload(
    @SerialName("post_id") val postId: String,
    @SerialName("author_user_id") val authorUserId: String,
    @SerialName("media_id") val mediaId: String,
    val visibility: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PostDeletedPayload(
    @SerialName("post_id") val postId: String,
    @SerialName("author_user_id") val authorUserId: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("deleted_at") val deletedAt: String,
)

@Serializable
data class PostCaptionUpdatedPayload(
    @SerialName("post_id") val postId: String,
    @SerialName("author_user_id") val authorUserId: String,
    @SerialName("updated_at") val updatedAt: String,
)

private val postEventJson = Json { encodeDefaults = true }

internal fun encodePostEventPayload(payload: PostCreatedPayload): String = postEventJson.encodeToString(payload)

internal fun encodePostEventPayload(payload: PostDeletedPayload): String = postEventJson.encodeToString(payload)

internal fun encodePostEventPayload(payload: PostCaptionUpdatedPayload): String = postEventJson.encodeToString(payload)
