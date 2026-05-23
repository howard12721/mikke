package jp.xhw.mikke.services.friendship.application.port

import jp.xhw.mikke.services.friendship.model.BlockRelation
import jp.xhw.mikke.services.friendship.model.FriendRequest
import jp.xhw.mikke.services.friendship.model.Friendship
import jp.xhw.mikke.services.friendship.model.UserId

interface FriendshipOutbox {
    fun appendFriendRequestRequested(request: FriendRequest)

    fun appendFriendRequestAccepted(
        request: FriendRequest,
        friendship: Friendship,
    )

    fun appendFriendRequestRejected(request: FriendRequest)

    fun appendFriendRequestCanceled(request: FriendRequest)

    fun appendFriendshipRemoved(friendship: Friendship)

    fun appendUserBlocked(block: BlockRelation)

    fun appendUserUnblocked(
        blockerUserId: UserId,
        blockedUserId: UserId,
    )
}
