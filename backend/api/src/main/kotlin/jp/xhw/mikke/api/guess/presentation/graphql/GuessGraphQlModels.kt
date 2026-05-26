package jp.xhw.mikke.api.guess.presentation.graphql

import jp.xhw.mikke.api.common.presentation.graphql.GeoPoint
import jp.xhw.mikke.api.common.presentation.graphql.GeoPointInput
import jp.xhw.mikke.api.common.presentation.graphql.PageInfo
import jp.xhw.mikke.api.common.presentation.graphql.toGraphQl

enum class GuessRankingMetric {
    GUESS_COUNT,
    TOTAL_SCORE,
    AVERAGE_SCORE,
}

data class SubmitGuessInput(
    val postId: String,
    val guessedPoint: GeoPointInput,
)

data class Guess(
    val id: String,
    val postId: String,
    val postAuthorUserId: String,
    val userId: String,
    val guessedPoint: GeoPoint,
    val distanceMeters: Double,
    val score: Int,
    val createdAt: String,
)

data class GuessResult(
    val guess: Guess,
    val correctPoint: GeoPoint,
    val distanceMeters: Double,
    val score: Int,
)

data class PostGuessStats(
    val postId: String,
    val guessCount: String,
    val averageDistanceMeters: Double,
    val bestDistanceMeters: Double,
    val averageScore: Double,
)

data class UserScoreSummary(
    val userId: String,
    val totalScore: String,
    val averageScore: Double,
    val guessCount: String,
    val bestDistanceMeters: Double,
)

data class PostUserRankingEntry(
    val userId: String,
    val rank: String,
    val postCount: String,
)

data class PostRankingPage(
    val entries: List<PostUserRankingEntry>,
    val pageInfo: PageInfo,
)

data class GuessUserRankingEntry(
    val userId: String,
    val rank: String,
    val totalScore: String,
    val averageScore: Double,
    val guessCount: String,
    val bestDistanceMeters: Double,
)

data class GuessRankingPage(
    val entries: List<GuessUserRankingEntry>,
    val pageInfo: PageInfo,
)

fun jp.xhw.mikke.api.guess.application.Guess.toGraphQl(): Guess =
    Guess(id, postId, postAuthorUserId, userId, guessedPoint.toGraphQl(), distanceMeters, score, createdAt)

fun jp.xhw.mikke.api.guess.application.GuessResult.toGraphQl(): GuessResult =
    GuessResult(guess.toGraphQl(), correctPoint.toGraphQl(), distanceMeters, score)

fun jp.xhw.mikke.api.guess.application.PostGuessStats.toGraphQl(): PostGuessStats =
    PostGuessStats(postId, guessCount.toString(), averageDistanceMeters, bestDistanceMeters, averageScore)

fun jp.xhw.mikke.api.guess.application.UserScoreSummary.toGraphQl(): UserScoreSummary =
    UserScoreSummary(userId, totalScore.toString(), averageScore, guessCount.toString(), bestDistanceMeters)

fun jp.xhw.mikke.api.guess.application.PostUserRankingEntry.toGraphQl(): PostUserRankingEntry =
    PostUserRankingEntry(userId, rank.toString(), postCount.toString())

fun jp.xhw.mikke.api.guess.application.GuessUserRankingEntry.toGraphQl(): GuessUserRankingEntry =
    GuessUserRankingEntry(
        userId,
        rank.toString(),
        totalScore.toString(),
        averageScore,
        guessCount.toString(),
        bestDistanceMeters,
    )
