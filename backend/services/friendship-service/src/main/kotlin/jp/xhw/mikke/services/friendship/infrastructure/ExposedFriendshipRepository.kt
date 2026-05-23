package jp.xhw.mikke.services.friendship.infrastructure

import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.friendship.application.exception.FriendshipStateException
import jp.xhw.mikke.services.friendship.application.port.FriendshipRepository
import jp.xhw.mikke.services.friendship.model.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

class ExposedFriendshipRepository : FriendshipRepository {
    override fun save(friendship: Friendship) {
        try {
            FriendshipsTable.insert { row ->
                row[id] = friendship.id.value
                row[userLowId] = friendship.userLowId.value
                row[userHighId] = friendship.userHighId.value
                row[status] = friendship.status.toDatabaseValue()
                row[createdAt] = friendship.createdAt.toJavaInstant()
                row[removedAt] = friendship.removedAt?.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueConstraintViolation()) {
                throw FriendshipStateException("Friendship already exists", e)
            }
            throw e
        }
    }

    override fun update(friendship: Friendship) {
        FriendshipsTable.update({ FriendshipsTable.id eq friendship.id.value }) { row ->
            row[status] = friendship.status.toDatabaseValue()
            row[createdAt] = friendship.createdAt.toJavaInstant()
            row[removedAt] = friendship.removedAt?.toJavaInstant()
        }
    }

    override fun findByPair(pair: NormalizedUserPair): Friendship? =
        FriendshipsTable
            .selectAll()
            .where {
                (FriendshipsTable.userLowId eq pair.low.value) and
                    (FriendshipsTable.userHighId eq pair.high.value)
            }.limit(1)
            .singleOrNull()
            ?.toFriendship()

    override fun findActiveBetween(
        firstUserId: UserId,
        secondUserId: UserId,
    ): Friendship? {
        val pair = NormalizedUserPair.of(firstUserId, secondUserId)
        return findByPair(pair)?.takeIf { it.status == FriendshipStatus.ACTIVE }
    }

    override fun markRemoved(
        id: FriendshipId,
        removedAt: Instant,
    ): Boolean {
        val removedAtJava = removedAt.toJavaInstant()
        val updated =
            FriendshipsTable.update({
                (FriendshipsTable.id eq id.value) and
                    (FriendshipsTable.status eq FriendshipStatus.ACTIVE.toDatabaseValue())
            }) { row ->
                row[status] = FriendshipStatus.REMOVED.toDatabaseValue()
                row[FriendshipsTable.removedAt] = removedAtJava
            }
        return updated > 0
    }

    override fun listActiveFriends(
        userId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Friendship> {
        if (limit <= 0) {
            return emptyList()
        }

        val membershipCondition =
            (FriendshipsTable.userLowId eq userId.value) or
                (FriendshipsTable.userHighId eq userId.value)

        val activeCondition = FriendshipsTable.status eq FriendshipStatus.ACTIVE.toDatabaseValue()

        val cursorCondition =
            cursor?.let {
                (FriendshipsTable.createdAt less it.createdAt.toJavaInstant()) or
                    (
                        (FriendshipsTable.createdAt eq it.createdAt.toJavaInstant()) and
                            (FriendshipsTable.id less it.id)
                    )
            }

        val whereClause =
            when (cursorCondition) {
                null -> membershipCondition and activeCondition
                else -> membershipCondition and activeCondition and cursorCondition
            }

        return FriendshipsTable
            .selectAll()
            .where { whereClause }
            .orderBy(
                FriendshipsTable.createdAt to SortOrder.DESC,
                FriendshipsTable.id to SortOrder.DESC,
            ).limit(limit)
            .map { it.toFriendship() }
    }
}

private object FriendshipsTable : Table("friendships") {
    val id = uuidBinary("id")
    val userLowId = uuidBinary("user_low_id")
    val userHighId = uuidBinary("user_high_id")
    val status = varchar("status", 32)
    val createdAt = timestamp("created_at")
    val removedAt = timestamp("removed_at").nullable()
}

private fun ResultRow.toFriendship(): Friendship =
    Friendship(
        id = FriendshipId(this[FriendshipsTable.id]),
        userLowId = UserId(this[FriendshipsTable.userLowId]),
        userHighId = UserId(this[FriendshipsTable.userHighId]),
        status = FriendshipStatus.fromDatabaseValue(this[FriendshipsTable.status]),
        createdAt = this[FriendshipsTable.createdAt].toKotlinInstant(),
        removedAt = this[FriendshipsTable.removedAt]?.toKotlinInstant(),
    )
