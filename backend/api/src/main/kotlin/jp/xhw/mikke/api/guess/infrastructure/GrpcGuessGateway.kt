package jp.xhw.mikke.api.guess.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.common.application.GeoPoint
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.common.infrastructure.call
import jp.xhw.mikke.api.common.infrastructure.toGeoPoint
import jp.xhw.mikke.api.common.infrastructure.toPageInfo
import jp.xhw.mikke.api.common.infrastructure.toProto
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.guess.application.*
import jp.xhw.mikke.api.guess.application.Guess
import jp.xhw.mikke.api.guess.application.GuessResult
import jp.xhw.mikke.api.guess.application.GuessUserRankingEntry
import jp.xhw.mikke.api.guess.application.PostGuessStats
import jp.xhw.mikke.api.guess.application.PostUserRankingEntry
import jp.xhw.mikke.api.guess.application.UserScoreSummary
import jp.xhw.mikke.api.infrastructure.authHeaderInterceptor
import jp.xhw.mikke.api.infrastructure.closeChannel
import jp.xhw.mikke.api.infrastructure.gatewayChannelFromEnvironment
import jp.xhw.mikke.api.infrastructure.toIsoString
import jp.xhw.mikke.guess.v1.*
import jp.xhw.mikke.guess.v1.Guess as ProtoGuess
import jp.xhw.mikke.guess.v1.GuessResult as ProtoGuessResult
import jp.xhw.mikke.guess.v1.GuessUserRankingEntry as ProtoGuessUserRankingEntry
import jp.xhw.mikke.guess.v1.PostGuessStats as ProtoPostGuessStats
import jp.xhw.mikke.guess.v1.PostUserRankingEntry as ProtoPostUserRankingEntry
import jp.xhw.mikke.guess.v1.UserScoreSummary as ProtoUserScoreSummary

class GrpcGuessGateway(
    private val channel: ManagedChannel,
    private val stub: GuessServiceGrpcKt.GuessServiceCoroutineStub =
        GuessServiceGrpcKt.GuessServiceCoroutineStub(channel),
) : GuessGateway {
    override suspend fun submitGuess(
        context: ApiRequestContext,
        postId: String,
        guessedPoint: GeoPoint,
    ): GuessResult =
        call {
            context
                .stub()
                .submitGuess(
                    SubmitGuessRequest
                        .newBuilder()
                        .setPostId(postId)
                        .setGuessedPoint(guessedPoint.toProto())
                        .build(),
                ).result
                .toGuessResult()
        }

    override suspend fun getMyGuessForPost(
        context: ApiRequestContext,
        postId: String,
    ): GuessResult? =
        call {
            val response =
                context
                    .stub()
                    .getMyGuessForPost(GetMyGuessForPostRequest.newBuilder().setPostId(postId).build())
            response.result.takeIf { it.hasGuess() }?.toGuessResult()
        }

    override suspend fun batchGetMyGuessesForPosts(
        context: ApiRequestContext,
        postIds: List<String>,
    ): List<GuessResult> =
        if (postIds.isEmpty()) {
            emptyList()
        } else {
            call {
                context
                    .stub()
                    .batchGetMyGuessesForPosts(
                        BatchGetMyGuessesForPostsRequest.newBuilder().addAllPostIds(postIds).build(),
                    ).resultsList
                    .map { it.toGuessResult() }
            }
        }

    override suspend fun getPostGuessStats(
        context: ApiRequestContext,
        postId: String,
    ): PostGuessStats =
        call {
            context
                .stub()
                .getPostGuessStats(GetPostGuessStatsRequest.newBuilder().setPostId(postId).build())
                .stats
                .toPostGuessStats()
        }

    override suspend fun getUserScoreSummary(
        context: ApiRequestContext,
        userId: String,
    ): UserScoreSummary =
        call {
            context
                .stub()
                .getUserScoreSummary(GetUserScoreSummaryRequest.newBuilder().setUserId(userId).build())
                .summary
                .toUserScoreSummary()
        }

    override suspend fun listPostRankings(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<PostUserRankingEntry> =
        call {
            val response =
                context.stub().listPostRankings(ListPostRankingsRequest.newBuilder().setPage(page.toProto()).build())
            PageResult(response.entriesList.map { it.toPostUserRankingEntry() }, response.pageInfo.toPageInfo())
        }

    override suspend fun listGuessRankings(
        context: ApiRequestContext,
        metric: String,
        page: PageInput,
    ): PageResult<GuessUserRankingEntry> =
        call {
            val response =
                context
                    .stub()
                    .listGuessRankings(
                        ListGuessRankingsRequest
                            .newBuilder()
                            .setMetric(metric.toGuessRankingMetric())
                            .setPage(page.toProto())
                            .build(),
                    )
            PageResult(response.entriesList.map { it.toGuessUserRankingEntry() }, response.pageInfo.toPageInfo())
        }

    override fun close() = closeChannel(channel)

    private fun ApiRequestContext.stub(): GuessServiceGrpcKt.GuessServiceCoroutineStub =
        authHeaderInterceptor(this)?.let { stub.withInterceptors(it) } ?: stub

    companion object {
        fun fromEnvironment(): GrpcGuessGateway =
            GrpcGuessGateway(
                gatewayChannelFromEnvironment(
                    targetEnv = "GUESS_SERVICE_TARGET",
                    hostEnv = "GUESS_SERVICE_HOST",
                    portEnv = "GUESS_SERVICE_PORT",
                    defaultPort = 50055,
                ),
            )
    }
}

private fun ProtoGuess.toGuess(): Guess =
    Guess(
        id = id,
        postId = postId,
        postAuthorUserId = postAuthorUserId,
        userId = userId,
        guessedPoint = guessedPoint.toGeoPoint(),
        distanceMeters = distanceMeters,
        score = score,
        createdAt = createdAt.toIsoString(),
    )

private fun ProtoGuessResult.toGuessResult(): GuessResult =
    GuessResult(
        guess = guess.toGuess(),
        correctPoint = correctPoint.toGeoPoint(),
        distanceMeters = distanceMeters,
        score = score,
    )

private fun ProtoPostGuessStats.toPostGuessStats(): PostGuessStats =
    PostGuessStats(
        postId = postId,
        guessCount = guessCount,
        averageDistanceMeters = averageDistanceMeters,
        bestDistanceMeters = bestDistanceMeters,
        averageScore = averageScore,
    )

private fun ProtoUserScoreSummary.toUserScoreSummary(): UserScoreSummary =
    UserScoreSummary(
        userId = userId,
        totalScore = totalScore,
        averageScore = averageScore,
        guessCount = guessCount,
        bestDistanceMeters = bestDistanceMeters,
    )

private fun ProtoPostUserRankingEntry.toPostUserRankingEntry(): PostUserRankingEntry =
    PostUserRankingEntry(
        userId = userId,
        rank = rank,
        postCount = postCount,
    )

private fun ProtoGuessUserRankingEntry.toGuessUserRankingEntry(): GuessUserRankingEntry =
    GuessUserRankingEntry(
        userId = userId,
        rank = rank,
        totalScore = totalScore,
        averageScore = averageScore,
        guessCount = guessCount,
        bestDistanceMeters = bestDistanceMeters,
    )

private fun String.toGuessRankingMetric(): GuessRankingMetric =
    when (uppercase()) {
        "GUESS_COUNT", "GUESS_RANKING_METRIC_GUESS_COUNT" -> GuessRankingMetric.GUESS_RANKING_METRIC_GUESS_COUNT
        "TOTAL_SCORE", "GUESS_RANKING_METRIC_TOTAL_SCORE" -> GuessRankingMetric.GUESS_RANKING_METRIC_TOTAL_SCORE
        "AVERAGE_SCORE", "GUESS_RANKING_METRIC_AVERAGE_SCORE" -> GuessRankingMetric.GUESS_RANKING_METRIC_AVERAGE_SCORE
        else -> GuessRankingMetric.GUESS_RANKING_METRIC_UNSPECIFIED
    }
