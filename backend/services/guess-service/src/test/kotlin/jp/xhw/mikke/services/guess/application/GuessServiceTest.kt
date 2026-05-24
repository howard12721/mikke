package jp.xhw.mikke.services.guess.application

import jp.xhw.mikke.events.guess.GuessEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.grpc.AlreadyExistsException
import jp.xhw.mikke.platform.grpc.PermissionDeniedException
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.services.guess.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GuessScoringTest {
    @Test
    fun `haversine returns zero for identical points`() {
        val point = GeoPoint(35.681236, 139.767125)
        assertEquals(0.0, GeoDistanceCalculator.haversineMeters(point, point), 0.001)
    }

    @Test
    fun `score is 1000 within perfect distance`() {
        assertEquals(1000, GuessScoreCalculator.calculateScore(0.0))
        assertEquals(1000, GuessScoreCalculator.calculateScore(25.0))
    }

    @Test
    fun `score decreases with distance and clamps at zero`() {
        assertEquals(909, GuessScoreCalculator.calculateScore(50.0))
        assertEquals(818, GuessScoreCalculator.calculateScore(100.0))
        assertEquals(0, GuessScoreCalculator.calculateScore(50_000.0))
        assertEquals(0, GuessScoreCalculator.calculateScore(100_000.0))
    }
}

class GuessServiceTest {
    private val viewerId = UserId(Uuid.random())
    private val authorId = UserId(Uuid.random())
    private val postId = PostId(Uuid.random())
    private val fixedInstant = Instant.fromEpochSeconds(1_700_000_000, 0)
    private val tokyo = GeoPoint(35.681236, 139.767125)
    private val nearbyGuess = GeoPoint(35.681500, 139.767500)

    private fun defaultPostAccess(): PostAccessPort =
        FakePostAccessPort(
            postId = postId,
            authorUserId = authorId,
            location = tokyo,
        )

    @Test
    fun `submitGuess saves guess updates stats and writes outbox`() {
        val repository = RecordingGuessRepository()
        val stats = RecordingGuessUserStatsRepository()
        val outbox = RecordingGuessOutboxRepository()
        val service = createService(repository = repository, stats = stats, outbox = outbox)

        val result =
            runBlocking {
                service.submitGuess(
                    SubmitGuessCommand(
                        postId = postId,
                        userId = viewerId,
                        guessedPoint = nearbyGuess,
                    ),
                )
            }

        assertEquals(viewerId, result.guess.userId)
        assertEquals(authorId, result.guess.postAuthorUserId)
        assertTrue(result.guess.distanceMeters >= 0.0)
        assertTrue(result.guess.score in 0..1000)
        assertEquals(tokyo, result.guess.correctPoint)
        assertEquals(1, repository.guesses.size)
        assertEquals(1, stats.submissions.size)
        assertEquals(GuessEventTypes.SUBMITTED, outbox.entries.single().eventType)
        assertTrue(
            outbox.entries
                .single()
                .payloadJson
                .contains("\"guess_id\""),
        )
    }

    @Test
    fun `submitGuess rejects duplicate guess`() {
        val repository = RecordingGuessRepository()
        val service = createService(repository = repository)

        runBlocking {
            service.submitGuess(
                SubmitGuessCommand(
                    postId = postId,
                    userId = viewerId,
                    guessedPoint = nearbyGuess,
                ),
            )
        }

        assertThrows(AlreadyExistsException::class.java) {
            runBlocking {
                service.submitGuess(
                    SubmitGuessCommand(
                        postId = postId,
                        userId = viewerId,
                        guessedPoint = nearbyGuess,
                    ),
                )
            }
        }
    }

    @Test
    fun `submitGuess rejects post author`() {
        val service = createService()

        assertThrows(PostAuthorCannotGuessException::class.java) {
            runBlocking {
                service.submitGuess(
                    SubmitGuessCommand(
                        postId = postId,
                        userId = authorId,
                        guessedPoint = nearbyGuess,
                    ),
                )
            }
        }
    }

    @Test
    fun `submitGuess does not fetch location when post is not visible`() {
        val service =
            createService(
                postAccess =
                    FakePostAccessPort(
                        postId = postId,
                        authorUserId = authorId,
                        location = tokyo,
                        canView = false,
                    ),
            )

        assertThrows(PermissionDeniedException::class.java) {
            runBlocking {
                service.submitGuess(
                    SubmitGuessCommand(
                        postId = postId,
                        userId = viewerId,
                        guessedPoint = nearbyGuess,
                    ),
                )
            }
        }
    }

    @Test
    fun `getMyGuessForPost returns null when unanswered`() {
        val service = createService()

        val result =
            runBlocking {
                service.getMyGuessForPost(postId = postId, viewerUserId = viewerId)
            }

        assertNull(result)
    }

    @Test
    fun `getMyGuessForPost returns result with correct point when answered`() {
        val repository = RecordingGuessRepository()
        val service = createService(repository = repository)

        runBlocking {
            service.submitGuess(
                SubmitGuessCommand(
                    postId = postId,
                    userId = viewerId,
                    guessedPoint = nearbyGuess,
                ),
            )
        }

        val result =
            runBlocking {
                service.getMyGuessForPost(postId = postId, viewerUserId = viewerId)
            }

        assertNotNull(result)
        assertEquals(tokyo, result!!.guess.correctPoint)
    }

    @Test
    fun `batchGetMyGuessesForPosts returns only answered posts with correct point`() {
        val repository = RecordingGuessRepository()
        val service = createService(repository = repository)
        val otherPostId = PostId(Uuid.random())

        runBlocking {
            service.submitGuess(
                SubmitGuessCommand(
                    postId = postId,
                    userId = viewerId,
                    guessedPoint = nearbyGuess,
                ),
            )
        }

        val results =
            runBlocking {
                service.batchGetMyGuessesForPosts(
                    postIds = listOf(postId, otherPostId),
                    viewerUserId = viewerId,
                )
            }

        assertEquals(1, results.size)
        assertEquals(postId, results.single().guess.postId)
        assertEquals(tokyo, results.single().guess.correctPoint)
    }

    @Test
    fun `getGuess returns correct point after post becomes inaccessible`() {
        val repository = RecordingGuessRepository()
        var canView = true
        val service =
            createService(
                repository = repository,
                postAccess =
                    FakePostAccessPort(
                        postId = postId,
                        authorUserId = authorId,
                        location = tokyo,
                        canViewProvider = { canView },
                    ),
            )

        val submitted =
            runBlocking {
                service.submitGuess(
                    SubmitGuessCommand(
                        postId = postId,
                        userId = viewerId,
                        guessedPoint = nearbyGuess,
                    ),
                )
            }

        canView = false

        val guess =
            runBlocking {
                service.getGuess(
                    guessId = submitted.guess.id,
                    viewerUserId = viewerId,
                )
            }

        assertEquals(tokyo, guess.correctPoint)
    }

    @Test
    fun `listGuessesForPost requires viewer to have answered`() {
        val service = createService()

        assertThrows(PermissionDeniedException::class.java) {
            runBlocking {
                service.listGuessesForPost(
                    postId = postId,
                    viewerUserId = viewerId,
                    limit = 20,
                    cursor = null,
                )
            }
        }
    }

    @Test
    fun `listGuessesForPost paginates with next cursor`() {
        val repository = RecordingGuessRepository()
        repository.guesses +=
            listOf(
                testGuess(userId = viewerId, createdAt = fixedInstant + 2.seconds),
                testGuess(userId = UserId(Uuid.random()), createdAt = fixedInstant + 1.seconds),
                testGuess(userId = UserId(Uuid.random()), createdAt = fixedInstant),
            )
        val service = createService(repository = repository)

        val page =
            runBlocking {
                service.listGuessesForPost(
                    postId = postId,
                    viewerUserId = viewerId,
                    limit = 2,
                    cursor = null,
                )
            }

        assertEquals(2, page.items.size)
        assertTrue(page.hasNextPage)
        assertNotNull(page.nextPageToken)
        assertEquals(viewerId, page.items.first().userId)
    }

    @Test
    fun `listMyGuesses paginates with next cursor`() {
        val repository = RecordingGuessRepository()
        repository.guesses +=
            listOf(
                testGuess(postId = PostId(Uuid.random()), createdAt = fixedInstant + 2.seconds),
                testGuess(postId = PostId(Uuid.random()), createdAt = fixedInstant + 1.seconds),
                testGuess(postId = PostId(Uuid.random()), createdAt = fixedInstant),
            )
        val service = createService(repository = repository)

        val page =
            runBlocking {
                service.listMyGuesses(
                    viewerUserId = viewerId,
                    limit = 2,
                    cursor = null,
                )
            }

        assertEquals(2, page.items.size)
        assertTrue(page.hasNextPage)
        assertNotNull(page.nextPageToken)
    }

    @Test
    fun `getPostGuessStats rejects when post is not visible and viewer has not guessed`() {
        val service =
            createService(
                postAccess =
                    FakePostAccessPort(
                        postId = postId,
                        authorUserId = authorId,
                        location = tokyo,
                        canView = false,
                    ),
            )

        assertThrows(PermissionDeniedException::class.java) {
            runBlocking {
                service.getPostGuessStats(postId = postId, viewerUserId = viewerId)
            }
        }
    }

    @Test
    fun `getPostGuessStats allows viewer who has guessed even when post is not visible`() {
        val repository = RecordingGuessRepository()
        repository.guesses += testGuess(userId = viewerId)
        val service =
            createService(
                repository = repository,
                postAccess =
                    FakePostAccessPort(
                        postId = postId,
                        authorUserId = authorId,
                        location = tokyo,
                        canView = false,
                    ),
            )

        val stats =
            runBlocking {
                service.getPostGuessStats(postId = postId, viewerUserId = viewerId)
            }

        assertEquals(1, stats.guessCount)
    }

    @Test
    fun `getPostGuessStats allows viewer when post is visible`() {
        val repository = RecordingGuessRepository()
        repository.guesses += testGuess(userId = UserId(Uuid.random()))
        val service = createService(repository = repository)

        val stats =
            runBlocking {
                service.getPostGuessStats(postId = postId, viewerUserId = viewerId)
            }

        assertEquals(1, stats.guessCount)
    }

    @Test
    fun `submitGuess rejects out of range latitude`() {
        val service = createService()

        assertThrows(ValidationException::class.java) {
            runBlocking {
                service.submitGuess(
                    SubmitGuessCommand(
                        postId = postId,
                        userId = viewerId,
                        guessedPoint = GeoPoint(latitude = 91.0, longitude = 0.0),
                    ),
                )
            }
        }
    }

    @Test
    fun `submitGuess rejects out of range longitude`() {
        val service = createService()

        assertThrows(ValidationException::class.java) {
            runBlocking {
                service.submitGuess(
                    SubmitGuessCommand(
                        postId = postId,
                        userId = viewerId,
                        guessedPoint = GeoPoint(latitude = 0.0, longitude = 181.0),
                    ),
                )
            }
        }
    }

    private fun testGuess(
        id: GuessId = GuessId(Uuid.random()),
        postId: PostId = this.postId,
        userId: UserId = viewerId,
        createdAt: Instant = fixedInstant,
    ): Guess =
        Guess(
            id = id,
            postId = postId,
            postAuthorUserId = authorId,
            userId = userId,
            guessedPoint = nearbyGuess,
            correctPoint = tokyo,
            distanceMeters = 10.0,
            score = 900,
            createdAt = createdAt,
        )

    private fun createService(
        repository: GuessRepository = RecordingGuessRepository(),
        stats: GuessUserStatsRepository = RecordingGuessUserStatsRepository(),
        postAuthorStats: PostAuthorStatsRepository = RecordingPostAuthorStatsRepository(),
        outbox: GuessOutboxRepository = RecordingGuessOutboxRepository(),
        postAccess: PostAccessPort = defaultPostAccess(),
    ): GuessService =
        GuessService(
            guessRepository = repository,
            guessUserStatsRepository = stats,
            postAuthorStatsRepository = postAuthorStats,
            guessOutboxRepository = outbox,
            postAccessPort = postAccess,
            transactionRunner = ImmediateTransactionRunner,
            clock =
                object : kotlin.time.Clock {
                    override fun now(): Instant = fixedInstant
                },
        )
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private class RecordingGuessRepository : GuessRepository {
    val guesses = mutableListOf<Guess>()

    override fun save(guess: Guess) {
        if (guesses.any { it.postId == guess.postId && it.userId == guess.userId }) {
            throw DuplicateGuessException()
        }
        guesses += guess
    }

    override fun findById(id: GuessId): Guess? = guesses.firstOrNull { it.id == id }

    override fun findByPostAndUser(
        postId: PostId,
        userId: UserId,
    ): Guess? = guesses.firstOrNull { it.postId == postId && it.userId == userId }

    override fun findByPostsAndUser(
        postIds: List<PostId>,
        userId: UserId,
    ): List<Guess> = postIds.mapNotNull { postId -> findByPostAndUser(postId, userId) }

    override fun listByPost(
        postId: PostId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Guess> =
        guesses
            .filter { it.postId == postId }
            .sortedWith(compareByDescending<Guess> { it.createdAt }.thenByDescending { it.id.value })
            .take(limit)

    override fun listByUser(
        userId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Guess> =
        guesses
            .filter { it.userId == userId }
            .sortedWith(compareByDescending<Guess> { it.createdAt }.thenByDescending { it.id.value })
            .take(limit)

    override fun hasUserGuessedPost(
        postId: PostId,
        userId: UserId,
    ): Boolean = guesses.any { it.postId == postId && it.userId == userId }

    override fun getPostStats(postId: PostId): PostGuessStats {
        val postGuesses = guesses.filter { it.postId == postId }
        if (postGuesses.isEmpty()) {
            return PostGuessStats(postId, 0, 0.0, null, 0.0)
        }
        return PostGuessStats(
            postId = postId,
            guessCount = postGuesses.size.toLong(),
            averageDistanceMeters = postGuesses.map { it.distanceMeters }.average(),
            bestDistanceMeters = postGuesses.minOf { it.distanceMeters },
            averageScore = postGuesses.map { it.score.toDouble() }.average(),
        )
    }
}

private class RecordingGuessUserStatsRepository : GuessUserStatsRepository {
    val submissions = mutableListOf<Triple<UserId, Int, Double>>()

    override fun applyGuessSubmission(
        userId: UserId,
        score: Int,
        distanceMeters: Double,
    ) {
        submissions += Triple(userId, score, distanceMeters)
    }

    override fun findByUserId(userId: UserId): UserScoreSummary? = null

    override fun listRankings(
        metric: GuessRankingMetric,
        limit: Int,
        offset: Int,
    ): List<GuessUserRankingEntry> = emptyList()
}

private class RecordingPostAuthorStatsRepository : PostAuthorStatsRepository {
    override fun incrementPostCount(userId: UserId) = Unit

    override fun decrementPostCount(userId: UserId) = Unit

    override fun listRankings(
        limit: Int,
        offset: Int,
    ): List<PostAuthorRankingEntry> = emptyList()
}

private class RecordingGuessOutboxRepository : GuessOutboxRepository {
    val entries = mutableListOf<OutboxEntry>()

    override fun append(entry: OutboxEntry) {
        entries += entry
    }
}

private class FakePostAccessPort(
    private val postId: PostId,
    private val authorUserId: UserId,
    private val location: GeoPoint,
    private val canView: Boolean = true,
    private val canViewProvider: (() -> Boolean)? = null,
) : PostAccessPort {
    override suspend fun canViewPost(postId: PostId): Boolean = canViewProvider?.invoke() ?: canView

    override suspend fun getPostLocationForGuess(postId: PostId): PostLocationForGuess {
        if (!(canViewProvider?.invoke() ?: canView)) {
            throw AssertionError("getPostLocationForGuess must not be called when post is not visible")
        }
        if (postId != this.postId) {
            error("unexpected post id")
        }
        return PostLocationForGuess(
            postId = postId,
            authorUserId = authorUserId,
            location = location,
        )
    }
}
