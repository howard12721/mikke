package jp.xhw.mikke.services.guess.infrastructure

import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.guess.application.PostAuthorStatsRepository
import jp.xhw.mikke.services.guess.model.PostAuthorRankingEntry
import jp.xhw.mikke.services.guess.model.UserId
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Clock

object PostAuthorStatsTable : Table("post_author_stats") {
    val userId = uuidBinary("user_id")
    val postCount = long("post_count")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

class ExposedPostAuthorStatsRepository(
    private val clock: Clock = Clock.System,
) : PostAuthorStatsRepository {
    override fun incrementPostCount(userId: UserId) {
        val now = clock.now()
        PostAuthorStatsTable.upsert(
            onUpdate = { statement ->
                statement[PostAuthorStatsTable.postCount] = PostAuthorStatsTable.postCount + 1L
                statement[PostAuthorStatsTable.updatedAt] = now.toJavaInstant()
            },
        ) {
            it[this.userId] = userId.value
            it[postCount] = 1
            it[updatedAt] = now.toJavaInstant()
        }
    }

    override fun decrementPostCount(userId: UserId) {
        val now = clock.now()
        PostAuthorStatsTable.upsert(
            onUpdate = { statement ->
                statement[PostAuthorStatsTable.postCount] =
                    CustomFunction(
                        functionName = "GREATEST",
                        columnType = LongColumnType(),
                        PostAuthorStatsTable.postCount - 1L,
                        longLiteral(0),
                    )
                statement[PostAuthorStatsTable.updatedAt] = now.toJavaInstant()
            },
        ) {
            it[this.userId] = userId.value
            it[postCount] = 0
            it[updatedAt] = now.toJavaInstant()
        }
    }

    override fun listRankings(
        limit: Int,
        offset: Int,
    ): List<PostAuthorRankingEntry> {
        val rows =
            PostAuthorStatsTable
                .selectAll()
                .where { PostAuthorStatsTable.postCount greater 0L }
                .orderBy(
                    PostAuthorStatsTable.postCount to SortOrder.DESC,
                    PostAuthorStatsTable.userId to SortOrder.ASC,
                ).limit(limit)
                .offset(offset.toLong())
                .toList()

        return rows.mapIndexed { index, row ->
            PostAuthorRankingEntry(
                userId = UserId(row[PostAuthorStatsTable.userId]),
                rank = offset + index + 1L,
                postCount = row[PostAuthorStatsTable.postCount],
            )
        }
    }
}
