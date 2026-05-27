package jp.xhw.mikke.services.guess.application

import jp.xhw.mikke.events.guess.GuessEventTypes
import jp.xhw.mikke.events.guess.GuessSubmittedPayload
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.grpc.NotFoundException
import jp.xhw.mikke.platform.grpc.PermissionDeniedException
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.platform.pagination.PageSlice
import jp.xhw.mikke.platform.pagination.buildPageSlice
import jp.xhw.mikke.services.guess.model.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val AGGREGATE_TYPE = "guess"
private const val PRODUCER = "guess-service"

class GuessService(
    private val guessRepository: GuessRepository,
    private val guessUserStatsRepository: GuessUserStatsRepository,
    private val postAuthorStatsRepository: PostAuthorStatsRepository,
    private val guessOutboxRepository: GuessOutboxRepository,
    private val postAccessPort: PostAccessPort,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock = Clock.System,
) {
    suspend fun submitGuess(command: SubmitGuessCommand): GuessResult {
        validateGeoPoint(command.guessedPoint, fieldPrefix = "guessed_point")

        if (!postAccessPort.canViewPost(command.postId, command.userId)) {
            throw PermissionDeniedException("post is not visible to viewer")
        }

        val postLocation = postAccessPort.getPostLocationForGuess(command.postId)
        if (command.userId == postLocation.authorUserId) {
            throw PostAuthorCannotGuessException()
        }

        val distanceMeters =
            GeoDistanceCalculator.haversineMeters(
                from = command.guessedPoint,
                to = postLocation.location,
            )
        val score = GuessScoreCalculator.calculateScore(distanceMeters)
        val now = clock.now()

        val guess =
            Guess(
                id = GuessId(Uuid.random()),
                postId = command.postId,
                postAuthorUserId = postLocation.authorUserId,
                userId = command.userId,
                guessedPoint = command.guessedPoint,
                correctPoint = postLocation.location,
                distanceMeters = distanceMeters,
                score = score,
                createdAt = now,
            )

        return transactionRunner.runInTransaction {
            try {
                guessRepository.save(guess)
            } catch (_: DuplicateGuessException) {
                throw jp.xhw.mikke.platform.grpc
                    .AlreadyExistsException("guess already submitted for this post")
            }

            guessUserStatsRepository.applyGuessSubmission(
                userId = command.userId,
                score = score,
                distanceMeters = distanceMeters,
            )

            guessOutboxRepository.append(
                OutboxEntry(
                    id = Uuid.random(),
                    eventType = GuessEventTypes.SUBMITTED,
                    aggregateType = AGGREGATE_TYPE,
                    aggregateId = guess.id.value,
                    payloadJson =
                        encodeGuessSubmittedPayload(
                            GuessSubmittedPayload(
                                guessId = guess.id.value.toString(),
                                postId = guess.postId.value.toString(),
                                postAuthorUserId = guess.postAuthorUserId.value.toString(),
                                userId = guess.userId.value.toString(),
                                distanceMeters = guess.distanceMeters,
                                score = guess.score,
                                submittedAt = guess.createdAt.toString(),
                            ),
                        ),
                    createdAt = now,
                ),
            )

            GuessResult(guess = guess)
        }
    }

    fun getGuess(
        guessId: GuessId,
        viewerUserId: UserId,
    ): Guess {
        val guess =
            transactionRunner.runInTransaction {
                guessRepository.findById(guessId)
            } ?: throw NotFoundException("guess not found")

        authorizeGuessView(guess, viewerUserId)
        return guess
    }

    fun getMyGuessForPost(
        postId: PostId,
        viewerUserId: UserId,
    ): GuessResult? {
        val guess =
            transactionRunner.runInTransaction {
                guessRepository.findByPostAndUser(postId, viewerUserId)
            } ?: return null

        return GuessResult(guess = guess)
    }

    fun batchGetMyGuessesForPosts(
        postIds: List<PostId>,
        viewerUserId: UserId,
    ): List<GuessResult> {
        if (postIds.isEmpty()) {
            return emptyList()
        }

        val guesses =
            transactionRunner.runInTransaction {
                guessRepository.findByPostsAndUser(postIds, viewerUserId)
            }

        return guesses.map { GuessResult(guess = it) }
    }

    fun listGuessesForPost(
        postId: PostId,
        viewerUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): PageSlice<Guess> {
        val viewerHasGuessed =
            transactionRunner.runInTransaction {
                guessRepository.hasUserGuessedPost(postId, viewerUserId)
            }
        if (!viewerHasGuessed) {
            throw PermissionDeniedException("viewer must submit a guess before listing guesses for this post")
        }

        val guesses =
            transactionRunner.runInTransaction {
                guessRepository.listByPost(postId, limit = limit + 1, cursor = cursor)
            }

        val nextCursor =
            if (guesses.size > limit) {
                val boundary = guesses[limit - 1]
                CreatedAtIdCursor(createdAt = boundary.createdAt, id = boundary.id.value)
            } else {
                null
            }

        return buildPageSlice(
            items = guesses,
            limit = limit,
            nextCursor = nextCursor,
        )
    }

    fun listMyGuesses(
        viewerUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): PageSlice<Guess> {
        val guesses =
            transactionRunner.runInTransaction {
                guessRepository.listByUser(viewerUserId, limit = limit + 1, cursor = cursor)
            }

        val nextCursor =
            if (guesses.size > limit) {
                val boundary = guesses[limit - 1]
                CreatedAtIdCursor(createdAt = boundary.createdAt, id = boundary.id.value)
            } else {
                null
            }

        return buildPageSlice(
            items = guesses,
            limit = limit,
            nextCursor = nextCursor,
        )
    }

    suspend fun getPostGuessStats(
        postId: PostId,
        viewerUserId: UserId,
    ): PostGuessStats {
        if (!canViewPostStats(postId, viewerUserId)) {
            throw PermissionDeniedException("post is not visible to viewer")
        }

        return transactionRunner.runInTransaction {
            guessRepository.getPostStats(postId)
        }
    }

    fun getUserScoreSummary(userId: UserId): UserScoreSummary =
        transactionRunner.runInTransaction {
            guessUserStatsRepository.findByUserId(userId)
                ?: UserScoreSummary(
                    userId = userId,
                    totalScore = 0,
                    averageScore = 0.0,
                    guessCount = 0,
                    bestDistanceMeters = null,
                )
        }

    fun listPostRankings(
        limit: Int,
        offset: Int,
    ): PageSlice<PostAuthorRankingEntry> {
        val entries =
            transactionRunner.runInTransaction {
                postAuthorStatsRepository.listRankings(limit = limit + 1, offset = offset)
            }

        return buildOffsetPageSlice(entries, limit, offset)
    }

    fun listGuessRankings(
        metric: GuessRankingMetric,
        limit: Int,
        offset: Int,
    ): PageSlice<GuessUserRankingEntry> {
        val entries =
            transactionRunner.runInTransaction {
                guessUserStatsRepository.listRankings(metric = metric, limit = limit + 1, offset = offset)
            }

        return buildOffsetPageSlice(entries, limit, offset)
    }

    private fun authorizeGuessView(
        guess: Guess,
        viewerUserId: UserId,
    ) {
        if (viewerUserId == guess.userId || viewerUserId == guess.postAuthorUserId) {
            return
        }

        val viewerHasGuessed =
            transactionRunner.runInTransaction {
                guessRepository.hasUserGuessedPost(guess.postId, viewerUserId)
            }
        if (viewerHasGuessed) {
            return
        }

        throw PermissionDeniedException("guess is not visible to viewer")
    }

    private suspend fun canViewPostStats(
        postId: PostId,
        viewerUserId: UserId,
    ): Boolean {
        val hasGuessed =
            transactionRunner.runInTransaction {
                guessRepository.hasUserGuessedPost(postId, viewerUserId)
            }
        if (hasGuessed) {
            return true
        }

        return postAccessPort.canViewPost(postId, viewerUserId)
    }

    private fun validateGeoPoint(
        point: GeoPoint,
        fieldPrefix: String,
    ) {
        if (point.latitude !in -90.0..90.0) {
            throw ValidationException("$fieldPrefix.latitude must be between -90 and 90")
        }
        if (point.longitude !in -180.0..180.0) {
            throw ValidationException("$fieldPrefix.longitude must be between -180 and 180")
        }
    }

    private fun <T> buildOffsetPageSlice(
        entries: List<T>,
        limit: Int,
        offset: Int,
    ): PageSlice<T> {
        val hasNextPage = entries.size > limit
        val pageItems = if (hasNextPage) entries.take(limit) else entries
        val nextOffset = offset + pageItems.size
        val nextPageToken = if (hasNextPage) OffsetPageCursor.encode(nextOffset) else null

        return PageSlice(
            items = pageItems,
            nextPageToken = nextPageToken,
            hasNextPage = hasNextPage,
        )
    }
}

data class SubmitGuessCommand(
    val postId: PostId,
    val userId: UserId,
    val guessedPoint: GeoPoint,
)
