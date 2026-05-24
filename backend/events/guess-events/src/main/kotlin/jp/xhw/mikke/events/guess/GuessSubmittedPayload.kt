package jp.xhw.mikke.events.guess

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GuessSubmittedPayload(
    @SerialName("guess_id") val guessId: String,
    @SerialName("post_id") val postId: String,
    @SerialName("post_author_user_id") val postAuthorUserId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("distance_meters") val distanceMeters: Double,
    val score: Int,
    @SerialName("submitted_at") val submittedAt: String,
)
