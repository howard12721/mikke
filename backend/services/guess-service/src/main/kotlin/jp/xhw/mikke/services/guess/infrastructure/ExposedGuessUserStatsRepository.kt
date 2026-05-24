package jp.xhw.mikke.services.guess.infrastructure

import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.guess.application.GuessUserStatsRepository
import jp.xhw.mikke.services.guess.model.GuessRankingMetric
import jp.xhw.mikke.services.guess.model.GuessUserRankingEntry
import jp.xhw.mikke.services.guess.model.UserId
import jp.xhw.mikke.services.guess.model.UserScoreSummary
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.CustomOperator
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.doubleLiteral
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Clock

object GuessUserStatsTable : Table("guess_user_stats") {
    val userId = uuidBinary("user_id")
    val guessCount = long("guess_count")
    val totalScore = long("total_score")
    val bestDistanceMeters = double("best_distance_meters").nullable()
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

class ExposedGuessUserStatsRepository(
    private val clock: Clock = Clock.System,
) : GuessUserStatsRepository {
    override fun applyGuessSubmission(
        userId: UserId,
        score: Int,
        distanceMeters: Double,
    ) {
        val now = clock.now()
        val submittedDistance = doubleLiteral(distanceMeters)

        GuessUserStatsTable.upsert(
            GuessUserStatsTable.userId,
            onUpdate = { statement ->
                statement[GuessUserStatsTable.guessCount] = GuessUserStatsTable.guessCount + 1L
                statement[GuessUserStatsTable.totalScore] =
                    GuessUserStatsTable.totalScore + score.toLong()
                statement[GuessUserStatsTable.bestDistanceMeters] =
                    CustomFunction(
                        functionName = "LEAST",
                        columnType = DoubleColumnType(),
                        coalesce(GuessUserStatsTable.bestDistanceMeters, submittedDistance),
                        submittedDistance,
                    )
                statement[GuessUserStatsTable.updatedAt] = now.toJavaInstant()
            },
        ) {
            it[this.userId] = userId.value
            it[guessCount] = 1
            it[totalScore] = score.toLong()
            it[bestDistanceMeters] = distanceMeters
            it[updatedAt] = now.toJavaInstant()
        }
    }

    override fun findByUserId(userId: UserId): UserScoreSummary? =
        GuessUserStatsTable
            .selectAll()
            .where { GuessUserStatsTable.userId eq userId.value }
            .limit(1)
            .singleOrNull()
            ?.toUserScoreSummary()

    override fun listRankings(
        metric: GuessRankingMetric,
        limit: Int,
        offset: Int,
    ): List<GuessUserRankingEntry> {
        val totalScoreAsDouble =
            CustomOperator<Double>(
                operatorName = "*",
                columnType = DoubleColumnType(),
                expr1 = GuessUserStatsTable.totalScore,
                expr2 = doubleLiteral(1.0),
            )
        val averageScore =
            CustomOperator<Double>(
                operatorName = "/",
                columnType = DoubleColumnType(),
                expr1 = totalScoreAsDouble,
                expr2 = GuessUserStatsTable.guessCount,
            )
        val metricOrder =
            when (metric) {
                GuessRankingMetric.GUESS_COUNT -> GuessUserStatsTable.guessCount
                GuessRankingMetric.TOTAL_SCORE -> GuessUserStatsTable.totalScore
                GuessRankingMetric.AVERAGE_SCORE -> averageScore
            }

        val rows =
            GuessUserStatsTable
                .selectAll()
                .where { GuessUserStatsTable.guessCount greater 0L }
                .orderBy(
                    metricOrder to SortOrder.DESC,
                    GuessUserStatsTable.userId to SortOrder.ASC,
                ).limit(limit)
                .offset(offset.toLong())
                .toList()

        return rows.mapIndexed { index, row ->
            val count = row[GuessUserStatsTable.guessCount]
            val total = row[GuessUserStatsTable.totalScore]
            GuessUserRankingEntry(
                userId = UserId(row[GuessUserStatsTable.userId]),
                rank = offset + index + 1L,
                totalScore = total,
                averageScore = if (count > 0) total.toDouble() / count else 0.0,
                guessCount = count,
                bestDistanceMeters = row[GuessUserStatsTable.bestDistanceMeters],
            )
        }
    }
}

private fun ResultRow.toUserScoreSummary(): UserScoreSummary {
    val count = this[GuessUserStatsTable.guessCount]
    val total = this[GuessUserStatsTable.totalScore]
    return UserScoreSummary(
        userId = UserId(this[GuessUserStatsTable.userId]),
        totalScore = total,
        averageScore = if (count > 0) total.toDouble() / count else 0.0,
        guessCount = count,
        bestDistanceMeters = this[GuessUserStatsTable.bestDistanceMeters],
    )
}
