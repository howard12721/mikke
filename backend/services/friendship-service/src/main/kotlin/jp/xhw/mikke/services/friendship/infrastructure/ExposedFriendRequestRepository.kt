package jp.xhw.mikke.services.friendship.infrastructure

import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.friendship.application.exception.DuplicateFriendRequestException
import jp.xhw.mikke.services.friendship.application.port.FriendRequestRepository
import jp.xhw.mikke.services.friendship.model.FriendRequest
import jp.xhw.mikke.services.friendship.model.FriendRequestId
import jp.xhw.mikke.services.friendship.model.FriendRequestStatus
import jp.xhw.mikke.services.friendship.model.UserId
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

class ExposedFriendRequestRepository : FriendRequestRepository {
    override fun save(request: FriendRequest) {
        try {
            FriendshipRequestsTable.insert { row ->
                row[id] = request.id.value
                row[senderUserId] = request.senderUserId.value
                row[receiverUserId] = request.receiverUserId.value
                row[status] = request.status.toDatabaseValue()
                row[createdAt] = request.createdAt.toJavaInstant()
                row[respondedAt] = request.respondedAt?.toJavaInstant()
                row[canceledAt] = request.canceledAt?.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueConstraintViolation()) {
                throw DuplicateFriendRequestException(cause = e)
            }
            throw e
        }
    }

    override fun update(request: FriendRequest) {
        FriendshipRequestsTable.update({ FriendshipRequestsTable.id eq request.id.value }) { row ->
            row[status] = request.status.toDatabaseValue()
            row[respondedAt] = request.respondedAt?.toJavaInstant()
            row[canceledAt] = request.canceledAt?.toJavaInstant()
        }
    }

    override fun findById(id: FriendRequestId): FriendRequest? =
        FriendshipRequestsTable
            .selectAll()
            .where { FriendshipRequestsTable.id eq id.value }
            .limit(1)
            .singleOrNull()
            ?.toFriendRequest()

    override fun findPendingBetween(
        firstUserId: UserId,
        secondUserId: UserId,
    ): FriendRequest? =
        FriendshipRequestsTable
            .selectAll()
            .where {
                (FriendshipRequestsTable.status eq FriendRequestStatus.PENDING.toDatabaseValue()) and
                    (
                        (
                            (FriendshipRequestsTable.senderUserId eq firstUserId.value) and
                                (FriendshipRequestsTable.receiverUserId eq secondUserId.value)
                        ) or
                            (
                                (FriendshipRequestsTable.senderUserId eq secondUserId.value) and
                                    (FriendshipRequestsTable.receiverUserId eq firstUserId.value)
                            )
                    )
            }.limit(1)
            .singleOrNull()
            ?.toFriendRequest()

    override fun listIncoming(
        receiverUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<FriendRequest> = listByDirection(receiverUserId, incoming = true, limit, cursor)

    override fun listOutgoing(
        senderUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<FriendRequest> = listByDirection(senderUserId, incoming = false, limit, cursor)

    override fun cancelPendingBetween(
        firstUserId: UserId,
        secondUserId: UserId,
        canceledAt: Instant,
    ): Int {
        val canceledAtJava = canceledAt.toJavaInstant()
        return FriendshipRequestsTable.update({
            (FriendshipRequestsTable.status eq FriendRequestStatus.PENDING.toDatabaseValue()) and
                (
                    (
                        (FriendshipRequestsTable.senderUserId eq firstUserId.value) and
                            (FriendshipRequestsTable.receiverUserId eq secondUserId.value)
                    ) or
                        (
                            (FriendshipRequestsTable.senderUserId eq secondUserId.value) and
                                (FriendshipRequestsTable.receiverUserId eq firstUserId.value)
                        )
                )
        }) { row ->
            row[status] = FriendRequestStatus.CANCELED.toDatabaseValue()
            row[FriendshipRequestsTable.canceledAt] = canceledAtJava
        }
    }

    private fun listByDirection(
        userId: UserId,
        incoming: Boolean,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<FriendRequest> {
        if (limit <= 0) {
            return emptyList()
        }

        val userColumn = if (incoming) FriendshipRequestsTable.receiverUserId else FriendshipRequestsTable.senderUserId
        val baseCondition =
            (userColumn eq userId.value) and
                (FriendshipRequestsTable.status eq FriendRequestStatus.PENDING.toDatabaseValue())

        val cursorCondition =
            cursor?.let {
                (FriendshipRequestsTable.createdAt less it.createdAt.toJavaInstant()) or
                    (
                        (FriendshipRequestsTable.createdAt eq it.createdAt.toJavaInstant()) and
                            (FriendshipRequestsTable.id less it.id)
                    )
            }

        val whereClause =
            when (cursorCondition) {
                null -> baseCondition
                else -> baseCondition and cursorCondition
            }

        return FriendshipRequestsTable
            .selectAll()
            .where { whereClause }
            .orderBy(
                FriendshipRequestsTable.createdAt to SortOrder.DESC,
                FriendshipRequestsTable.id to SortOrder.DESC,
            ).limit(limit)
            .map { it.toFriendRequest() }
    }
}

private object FriendshipRequestsTable : Table("friendship_requests") {
    val id = uuidBinary("id")
    val senderUserId = uuidBinary("sender_user_id")
    val receiverUserId = uuidBinary("receiver_user_id")
    val status = varchar("status", 32)
    val createdAt = timestamp("created_at")
    val respondedAt = timestamp("responded_at").nullable()
    val canceledAt = timestamp("canceled_at").nullable()
}

private fun ResultRow.toFriendRequest(): FriendRequest =
    FriendRequest(
        id = FriendRequestId(this[FriendshipRequestsTable.id]),
        senderUserId = UserId(this[FriendshipRequestsTable.senderUserId]),
        receiverUserId = UserId(this[FriendshipRequestsTable.receiverUserId]),
        status = FriendRequestStatus.fromDatabaseValue(this[FriendshipRequestsTable.status]),
        createdAt = this[FriendshipRequestsTable.createdAt].toKotlinInstant(),
        respondedAt = this[FriendshipRequestsTable.respondedAt]?.toKotlinInstant(),
        canceledAt = this[FriendshipRequestsTable.canceledAt]?.toKotlinInstant(),
    )
