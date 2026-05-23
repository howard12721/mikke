package jp.xhw.mikke.services.friendship.application.port

import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.services.friendship.model.Friendship
import jp.xhw.mikke.services.friendship.model.FriendshipId
import jp.xhw.mikke.services.friendship.model.NormalizedUserPair
import jp.xhw.mikke.services.friendship.model.UserId
import kotlin.time.Instant

interface FriendshipRepository {
    fun save(friendship: Friendship)

    fun update(friendship: Friendship)

    fun findByPair(pair: NormalizedUserPair): Friendship?

    fun findActiveBetween(
        firstUserId: UserId,
        secondUserId: UserId,
    ): Friendship?

    fun markRemoved(
        id: FriendshipId,
        removedAt: Instant,
    ): Boolean

    fun listActiveFriends(
        userId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Friendship>
}
