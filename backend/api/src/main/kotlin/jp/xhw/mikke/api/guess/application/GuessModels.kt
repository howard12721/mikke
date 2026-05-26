package jp.xhw.mikke.api.guess.application

import jp.xhw.mikke.api.common.application.GeoPoint
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.graphql.ApiRequestContext

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
    val guessCount: Long,
    val averageDistanceMeters: Double,
    val bestDistanceMeters: Double,
    val averageScore: Double,
)

data class UserScoreSummary(
    val userId: String,
    val totalScore: Long,
    val averageScore: Double,
    val guessCount: Long,
    val bestDistanceMeters: Double,
)

data class PostUserRankingEntry(
    val userId: String,
    val rank: Long,
    val postCount: Long,
)

data class GuessUserRankingEntry(
    val userId: String,
    val rank: Long,
    val totalScore: Long,
    val averageScore: Double,
    val guessCount: Long,
    val bestDistanceMeters: Double,
)

interface GuessGateway : AutoCloseable {
    suspend fun submitGuess(
        context: ApiRequestContext,
        postId: String,
        guessedPoint: GeoPoint,
    ): GuessResult

    suspend fun getMyGuessForPost(
        context: ApiRequestContext,
        postId: String,
    ): GuessResult?

    suspend fun batchGetMyGuessesForPosts(
        context: ApiRequestContext,
        postIds: List<String>,
    ): List<GuessResult>

    suspend fun getPostGuessStats(
        context: ApiRequestContext,
        postId: String,
    ): PostGuessStats

    suspend fun getUserScoreSummary(
        context: ApiRequestContext,
        userId: String,
    ): UserScoreSummary

    suspend fun listPostRankings(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<PostUserRankingEntry>

    suspend fun listGuessRankings(
        context: ApiRequestContext,
        metric: String,
        page: PageInput,
    ): PageResult<GuessUserRankingEntry>
}
