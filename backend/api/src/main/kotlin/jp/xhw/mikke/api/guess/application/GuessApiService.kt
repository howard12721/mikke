package jp.xhw.mikke.api.guess.application

import jp.xhw.mikke.api.common.application.*
import jp.xhw.mikke.api.graphql.ApiRequestContext

class GuessApiService(
    private val guessGateway: GuessGateway,
) {
    suspend fun submitGuess(
        context: ApiRequestContext,
        postId: String,
        guessedPoint: GeoPoint,
    ): GuessResult = guessGateway.submitGuess(context, postId.requireText("postId"), guessedPoint)

    suspend fun getMyGuessForPost(
        context: ApiRequestContext,
        postId: String,
    ): GuessResult? = guessGateway.getMyGuessForPost(context, postId.requireText("postId"))

    suspend fun getPostGuessStats(
        context: ApiRequestContext,
        postId: String,
    ): PostGuessStats = guessGateway.getPostGuessStats(context, postId.requireText("postId"))

    suspend fun getUserScoreSummary(
        context: ApiRequestContext,
        userId: String,
    ): UserScoreSummary = guessGateway.getUserScoreSummary(context, userId.requireText("userId"))

    suspend fun listPostRankings(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<PostUserRankingEntry> = guessGateway.listPostRankings(context, page.normalized())

    suspend fun listGuessRankings(
        context: ApiRequestContext,
        metric: String,
        page: PageInput,
    ): PageResult<GuessUserRankingEntry> = guessGateway.listGuessRankings(context, metric, page.normalized())
}
