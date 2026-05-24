package jp.xhw.mikke.services.guess.model

import kotlin.time.Instant

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class Guess(
    val id: GuessId,
    val postId: PostId,
    val postAuthorUserId: UserId,
    val userId: UserId,
    val guessedPoint: GeoPoint,
    val correctPoint: GeoPoint,
    val distanceMeters: Double,
    val score: Int,
    val createdAt: Instant,
)

data class GuessResult(
    val guess: Guess,
)

data class PostGuessStats(
    val postId: PostId,
    val guessCount: Long,
    val averageDistanceMeters: Double,
    val bestDistanceMeters: Double?,
    val averageScore: Double,
)

data class UserScoreSummary(
    val userId: UserId,
    val totalScore: Long,
    val averageScore: Double,
    val guessCount: Long,
    val bestDistanceMeters: Double?,
)

data class PostAuthorRankingEntry(
    val userId: UserId,
    val rank: Long,
    val postCount: Long,
)

enum class GuessRankingMetric {
    GUESS_COUNT,
    TOTAL_SCORE,
    AVERAGE_SCORE,
}

data class GuessUserRankingEntry(
    val userId: UserId,
    val rank: Long,
    val totalScore: Long,
    val averageScore: Double,
    val guessCount: Long,
    val bestDistanceMeters: Double?,
)
