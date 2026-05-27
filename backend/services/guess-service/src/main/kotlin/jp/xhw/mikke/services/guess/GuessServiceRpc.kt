package jp.xhw.mikke.services.guess

import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.common.v1.PageInfo
import jp.xhw.mikke.guess.v1.*
import jp.xhw.mikke.platform.grpc.*
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.services.guess.application.*
import jp.xhw.mikke.services.guess.model.GuessId
import jp.xhw.mikke.services.guess.model.PostId
import jp.xhw.mikke.services.guess.model.UserId
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("guess-service")

class GuessServiceRpc(
    private val guessService: GuessService,
) : GuessServiceGrpcKt.GuessServiceCoroutineImplBase() {
    override suspend fun submitGuess(request: SubmitGuessRequest): SubmitGuessResponse {
        val userId = request.actor.toUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        if (!request.hasGuessedPoint()) {
            throw ValidationException("guessed_point is required")
        }

        val result =
            mapRpcExceptions {
                guessService.submitGuess(
                    SubmitGuessCommand(
                        postId = postId,
                        userId = userId,
                        guessedPoint = request.guessedPoint.toDomain(),
                    ),
                )
            }

        return SubmitGuessResponse
            .newBuilder()
            .setResult(result.toProto())
            .build()
    }

    override suspend fun getGuess(request: GetGuessRequest): GetGuessResponse {
        val viewerUserId = request.actor.toUserId()
        val guessId = parseGrpcUuid(request.guessId, "guess_id").let(::GuessId)

        val guess =
            mapRpcExceptions {
                guessService.getGuess(guessId = guessId, viewerUserId = viewerUserId)
            }

        return GetGuessResponse
            .newBuilder()
            .setGuess(guess.toProto())
            .build()
    }

    override suspend fun getMyGuessForPost(request: GetMyGuessForPostRequest): GetMyGuessForPostResponse {
        val viewerUserId = request.actor.toUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        val result =
            mapRpcExceptions {
                guessService.getMyGuessForPost(postId = postId, viewerUserId = viewerUserId)
            }

        val builder = GetMyGuessForPostResponse.newBuilder()
        result?.let { builder.setResult(it.toProto()) }
        return builder.build()
    }

    override suspend fun batchGetMyGuessesForPosts(request: BatchGetMyGuessesForPostsRequest): BatchGetMyGuessesForPostsResponse {
        val viewerUserId = request.actor.toUserId()
        val postIds = request.postIdsList.map { parseGrpcUuid(it, "post_id").let(::PostId) }

        val results =
            mapRpcExceptions {
                guessService.batchGetMyGuessesForPosts(postIds = postIds, viewerUserId = viewerUserId)
            }

        return BatchGetMyGuessesForPostsResponse
            .newBuilder()
            .addAllResults(results.map { it.toProto() })
            .build()
    }

    override suspend fun listGuessesForPost(request: ListGuessesForPostRequest): ListGuessesForPostResponse {
        val viewerUserId = request.actor.toUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)
        val page =
            PageRequestInput(
                pageSize = request.page.pageSize,
                pageToken = request.page.pageToken,
            ).validate()

        val slice =
            mapRpcExceptions {
                guessService.listGuessesForPost(
                    postId = postId,
                    viewerUserId = viewerUserId,
                    limit = page.limit,
                    cursor = page.cursor,
                )
            }

        return ListGuessesForPostResponse
            .newBuilder()
            .addAllGuesses(slice.items.map { it.toProto() })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(slice.nextPageToken.orEmpty())
                    .setHasNextPage(slice.hasNextPage)
                    .build(),
            ).build()
    }

    override suspend fun listMyGuesses(request: ListMyGuessesRequest): ListMyGuessesResponse {
        val viewerUserId = request.actor.toUserId()
        val page =
            PageRequestInput(
                pageSize = request.page.pageSize,
                pageToken = request.page.pageToken,
            ).validate()

        val slice =
            mapRpcExceptions {
                guessService.listMyGuesses(
                    viewerUserId = viewerUserId,
                    limit = page.limit,
                    cursor = page.cursor,
                )
            }

        return ListMyGuessesResponse
            .newBuilder()
            .addAllGuesses(slice.items.map { it.toProto() })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(slice.nextPageToken.orEmpty())
                    .setHasNextPage(slice.hasNextPage)
                    .build(),
            ).build()
    }

    override suspend fun getPostGuessStats(request: GetPostGuessStatsRequest): GetPostGuessStatsResponse {
        val viewerUserId = request.actor.toUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        val stats =
            mapRpcExceptions {
                guessService.getPostGuessStats(postId = postId, viewerUserId = viewerUserId)
            }

        return GetPostGuessStatsResponse
            .newBuilder()
            .setStats(stats.toProto())
            .build()
    }

    override suspend fun getUserScoreSummary(request: GetUserScoreSummaryRequest): GetUserScoreSummaryResponse {
        val userId = parseGrpcUuid(request.userId, "user_id").let(::UserId)

        val summary =
            mapRpcExceptions {
                guessService.getUserScoreSummary(userId)
            }

        return GetUserScoreSummaryResponse
            .newBuilder()
            .setSummary(summary.toProto())
            .build()
    }

    override suspend fun listPostRankings(request: ListPostRankingsRequest): ListPostRankingsResponse {
        request.actor.toUserId()
        val slice =
            mapRpcExceptions {
                val page =
                    PageRequestInput(
                        pageSize = request.page.pageSize,
                        pageToken = request.page.pageToken,
                    ).validateOffset()
                val offset = page.cursor?.offset ?: 0

                guessService.listPostRankings(limit = page.limit, offset = offset)
            }

        return ListPostRankingsResponse
            .newBuilder()
            .addAllEntries(slice.items.map { it.toProto() })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(slice.nextPageToken.orEmpty())
                    .setHasNextPage(slice.hasNextPage)
                    .build(),
            ).build()
    }

    override suspend fun listGuessRankings(request: ListGuessRankingsRequest): ListGuessRankingsResponse {
        request.actor.toUserId()
        val slice =
            mapRpcExceptions {
                val metric = request.metric.toDomain()
                val page =
                    PageRequestInput(
                        pageSize = request.page.pageSize,
                        pageToken = request.page.pageToken,
                    ).validateOffset()
                val offset = page.cursor?.offset ?: 0

                guessService.listGuessRankings(metric = metric, limit = page.limit, offset = offset)
            }

        return ListGuessRankingsResponse
            .newBuilder()
            .addAllEntries(slice.items.map { it.toProto() })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(slice.nextPageToken.orEmpty())
                    .setHasNextPage(slice.hasNextPage)
                    .build(),
            ).build()
    }

    private fun ActorContext.toUserId(): UserId = UserId(requireUserUuid())
}

private suspend inline fun <T> mapRpcExceptions(crossinline block: suspend () -> T): T =
    withGrpcExceptionMapping(
        logger = logger,
        serviceName = "guess-service",
        internalErrorDescription = "Internal guess service error",
        domainExceptionMapper = { throwable -> throwable.toGuessGrpcStatus() },
    ) {
        block()
    }
