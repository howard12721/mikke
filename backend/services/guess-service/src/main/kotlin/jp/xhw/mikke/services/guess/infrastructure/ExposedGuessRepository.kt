package jp.xhw.mikke.services.guess.infrastructure

import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.guess.application.DuplicateGuessException
import jp.xhw.mikke.services.guess.application.GuessRepository
import jp.xhw.mikke.services.guess.model.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

object GuessesTable : Table("guesses") {
    val id = uuidBinary("id")
    val postId = uuidBinary("post_id")
    val postAuthorUserId = uuidBinary("post_author_user_id")
    val userId = uuidBinary("user_id")
    val guessedLatitude = decimal("guessed_latitude", 9, 6)
    val guessedLongitude = decimal("guessed_longitude", 9, 6)
    val correctLatitude = decimal("correct_latitude", 9, 6)
    val correctLongitude = decimal("correct_longitude", 9, 6)
    val distanceMeters = double("distance_meters")
    val score = integer("score")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_guesses_post_user", postId, userId)
    }
}

class ExposedGuessRepository : GuessRepository {
    override fun save(guess: Guess) {
        try {
            GuessesTable.insert { row ->
                row[id] = guess.id.value
                row[postId] = guess.postId.value
                row[postAuthorUserId] = guess.postAuthorUserId.value
                row[userId] = guess.userId.value
                row[guessedLatitude] = guess.guessedPoint.latitude.toBigDecimal()
                row[guessedLongitude] = guess.guessedPoint.longitude.toBigDecimal()
                row[correctLatitude] = guess.correctPoint.latitude.toBigDecimal()
                row[correctLongitude] = guess.correctPoint.longitude.toBigDecimal()
                row[distanceMeters] = guess.distanceMeters
                row[score] = guess.score
                row[createdAt] = guess.createdAt.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueConstraintViolation()) {
                throw DuplicateGuessException(cause = e)
            }
            throw e
        }
    }

    override fun findById(id: GuessId): Guess? =
        GuessesTable
            .selectAll()
            .where { GuessesTable.id eq id.value }
            .limit(1)
            .singleOrNull()
            ?.toGuess()

    override fun findByPostAndUser(
        postId: PostId,
        userId: UserId,
    ): Guess? =
        GuessesTable
            .selectAll()
            .where { (GuessesTable.postId eq postId.value) and (GuessesTable.userId eq userId.value) }
            .limit(1)
            .singleOrNull()
            ?.toGuess()

    override fun findByPostsAndUser(
        postIds: List<PostId>,
        userId: UserId,
    ): List<Guess> {
        if (postIds.isEmpty()) {
            return emptyList()
        }

        val byPostId =
            GuessesTable
                .selectAll()
                .where {
                    (GuessesTable.postId inList postIds.map { it.value }.distinct()) and
                        (GuessesTable.userId eq userId.value)
                }.map { it.toGuess() }
                .associateBy { it.postId }

        return postIds.mapNotNull { byPostId[it] }
    }

    override fun listByPost(
        postId: PostId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Guess> {
        val query =
            GuessesTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> = GuessesTable.postId eq postId.value
                    if (cursor != null) {
                        condition =
                            condition and
                            (
                                (GuessesTable.createdAt less cursor.createdAt.toJavaInstant()) or
                                    (
                                        (GuessesTable.createdAt eq cursor.createdAt.toJavaInstant()) and
                                            (GuessesTable.id less cursor.id)
                                    )
                            )
                    }
                    condition
                }.orderBy(GuessesTable.createdAt to SortOrder.DESC, GuessesTable.id to SortOrder.DESC)
                .limit(limit)

        return query.map { it.toGuess() }
    }

    override fun listByUser(
        userId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Guess> {
        val query =
            GuessesTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> = GuessesTable.userId eq userId.value
                    if (cursor != null) {
                        condition =
                            condition and
                            (
                                (GuessesTable.createdAt less cursor.createdAt.toJavaInstant()) or
                                    (
                                        (GuessesTable.createdAt eq cursor.createdAt.toJavaInstant()) and
                                            (GuessesTable.id less cursor.id)
                                    )
                            )
                    }
                    condition
                }.orderBy(GuessesTable.createdAt to SortOrder.DESC, GuessesTable.id to SortOrder.DESC)
                .limit(limit)

        return query.map { it.toGuess() }
    }

    override fun hasUserGuessedPost(
        postId: PostId,
        userId: UserId,
    ): Boolean =
        GuessesTable
            .selectAll()
            .where { (GuessesTable.postId eq postId.value) and (GuessesTable.userId eq userId.value) }
            .limit(1)
            .any()

    override fun getPostStats(postId: PostId): PostGuessStats {
        val guessCount = GuessesTable.id.count()
        val averageDistance = GuessesTable.distanceMeters.avg()
        val bestDistance = GuessesTable.distanceMeters.min()
        val averageScore = GuessesTable.score.avg()

        val row =
            GuessesTable
                .select(guessCount, averageDistance, bestDistance, averageScore)
                .where { GuessesTable.postId eq postId.value }
                .single()

        val count = row[guessCount]
        if (count == 0L) {
            return PostGuessStats(
                postId = postId,
                guessCount = 0,
                averageDistanceMeters = 0.0,
                bestDistanceMeters = null,
                averageScore = 0.0,
            )
        }

        return PostGuessStats(
            postId = postId,
            guessCount = count,
            averageDistanceMeters = row[averageDistance]?.toDouble() ?: 0.0,
            bestDistanceMeters = row[bestDistance],
            averageScore = row[averageScore]?.toDouble() ?: 0.0,
        )
    }
}

private fun ResultRow.toGuess(): Guess =
    Guess(
        id = GuessId(this[GuessesTable.id]),
        postId = PostId(this[GuessesTable.postId]),
        postAuthorUserId = UserId(this[GuessesTable.postAuthorUserId]),
        userId = UserId(this[GuessesTable.userId]),
        guessedPoint =
            GeoPoint(
                latitude = this[GuessesTable.guessedLatitude].toPlainString().toDouble(),
                longitude = this[GuessesTable.guessedLongitude].toPlainString().toDouble(),
            ),
        correctPoint =
            GeoPoint(
                latitude = this[GuessesTable.correctLatitude].toPlainString().toDouble(),
                longitude = this[GuessesTable.correctLongitude].toPlainString().toDouble(),
            ),
        distanceMeters = this[GuessesTable.distanceMeters],
        score = this[GuessesTable.score],
        createdAt = this[GuessesTable.createdAt].toKotlinInstant(),
    )
