package jp.xhw.mikke.services.guess.application

import jp.xhw.mikke.events.guess.GuessSubmittedPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GuessSubmittedOutboxPayload(
    @SerialName("guess_id") val guessId: String,
    @SerialName("post_id") val postId: String,
    @SerialName("post_author_user_id") val postAuthorUserId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("distance_meters") val distanceMeters: Double,
    val score: Int,
    @SerialName("submitted_at") val submittedAt: String,
)

private val guessEventJson = Json { encodeDefaults = true }

internal fun encodeGuessSubmittedPayload(payload: GuessSubmittedPayload): String =
    guessEventJson.encodeToString(
        GuessSubmittedOutboxPayload(
            guessId = payload.guessId,
            postId = payload.postId,
            postAuthorUserId = payload.postAuthorUserId,
            userId = payload.userId,
            distanceMeters = payload.distanceMeters,
            score = payload.score,
            submittedAt = payload.submittedAt,
        ),
    )
