package jp.xhw.mikke.events.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaUploadCompletedPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("uploader_user_id") val uploaderUserId: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("content_length_bytes") val contentLengthBytes: Long,
    val etag: String,
    @SerialName("uploaded_at") val uploadedAt: String,
)
