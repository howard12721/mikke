package jp.xhw.mikke.services.guess.application

import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.services.guess.model.*

interface GuessRepository {
    fun save(guess: Guess)

    fun findById(id: GuessId): Guess?

    fun findByPostAndUser(
        postId: PostId,
        userId: UserId,
    ): Guess?

    fun findByPostsAndUser(
        postIds: List<PostId>,
        userId: UserId,
    ): List<Guess>

    fun listByPost(
        postId: PostId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Guess>

    fun listByUser(
        userId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Guess>

    fun hasUserGuessedPost(
        postId: PostId,
        userId: UserId,
    ): Boolean

    fun getPostStats(postId: PostId): PostGuessStats
}

interface GuessUserStatsRepository {
    fun applyGuessSubmission(
        userId: UserId,
        score: Int,
        distanceMeters: Double,
    )

    fun findByUserId(userId: UserId): UserScoreSummary?

    fun listRankings(
        metric: GuessRankingMetric,
        limit: Int,
        offset: Int,
    ): List<GuessUserRankingEntry>
}

interface PostAuthorStatsRepository {
    fun incrementPostCount(userId: UserId)

    fun decrementPostCount(userId: UserId)

    fun listRankings(
        limit: Int,
        offset: Int,
    ): List<PostAuthorRankingEntry>
}

interface GuessOutboxRepository {
    fun append(entry: OutboxEntry)
}

data class PostLocationForGuess(
    val postId: PostId,
    val authorUserId: UserId,
    val location: GeoPoint,
)

interface PostAccessPort {
    suspend fun canViewPost(
        postId: PostId,
        viewerUserId: UserId,
    ): Boolean

    suspend fun getPostLocationForGuess(postId: PostId): PostLocationForGuess
}

class DuplicateGuessException(
    cause: Throwable? = null,
) : RuntimeException("guess already submitted for this post", cause)

class PostAuthorCannotGuessException : RuntimeException("post author cannot submit a guess for their own post")
