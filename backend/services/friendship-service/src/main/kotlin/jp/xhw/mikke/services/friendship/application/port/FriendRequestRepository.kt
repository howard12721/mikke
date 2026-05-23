package jp.xhw.mikke.services.friendship.application.port

import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.services.friendship.model.FriendRequest
import jp.xhw.mikke.services.friendship.model.FriendRequestId
import jp.xhw.mikke.services.friendship.model.UserId

interface FriendRequestRepository {
    fun save(request: FriendRequest)

    fun update(request: FriendRequest)

    fun findById(id: FriendRequestId): FriendRequest?

    fun findPendingBetween(
        firstUserId: UserId,
        secondUserId: UserId,
    ): FriendRequest?

    fun listIncoming(
        receiverUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<FriendRequest>

    fun listOutgoing(
        senderUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<FriendRequest>

    fun cancelPendingBetween(
        firstUserId: UserId,
        secondUserId: UserId,
        canceledAt: kotlin.time.Instant,
    ): Int
}
