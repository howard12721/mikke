package jp.xhw.mikke.events.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaThumbnailReadyPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("content_length_bytes") val contentLengthBytes: Long,
    val width: Int?,
    val height: Int?,
    @SerialName("ready_at") val readyAt: String,
)
