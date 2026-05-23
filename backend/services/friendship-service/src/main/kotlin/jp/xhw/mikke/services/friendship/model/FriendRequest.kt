package jp.xhw.mikke.services.friendship.model

import kotlin.time.Instant

data class FriendRequest(
    val id: FriendRequestId,
    val senderUserId: UserId,
    val receiverUserId: UserId,
    val status: FriendRequestStatus,
    val createdAt: Instant,
    val respondedAt: Instant?,
    val canceledAt: Instant?,
)
