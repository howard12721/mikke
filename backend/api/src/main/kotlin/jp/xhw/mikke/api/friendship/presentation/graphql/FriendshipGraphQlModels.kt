package jp.xhw.mikke.api.friendship.presentation.graphql

import jp.xhw.mikke.api.common.presentation.graphql.PageInfo

data class SendFriendRequestInput(
    val receiverUserId: String,
)

data class FriendRequestIdInput(
    val friendRequestId: String,
)

data class UserIdInput(
    val userId: String,
)

data class FriendRequest(
    val id: String,
    val senderUserId: String,
    val receiverUserId: String,
    val status: String,
    val createdAt: String,
    val respondedAt: String?,
    val canceledAt: String?,
)

data class Friendship(
    val id: String,
    val userLowId: String,
    val userHighId: String,
    val status: String,
    val createdAt: String,
    val removedAt: String?,
)

data class BlockRelation(
    val blockerUserId: String,
    val blockedUserId: String,
    val createdAt: String,
)

data class FriendshipSummary(
    val targetUserId: String,
    val relationStatus: String,
    val canViewPosts: Boolean,
    val canSendRequest: Boolean,
)

data class FriendUserIdsPage(
    val userIds: List<String>,
    val pageInfo: PageInfo,
)

data class FriendRequestPage(
    val requests: List<FriendRequest>,
    val pageInfo: PageInfo,
)

data class BooleanPayload(
    val success: Boolean,
)

fun jp.xhw.mikke.api.friendship.application.FriendRequest.toGraphQl(): FriendRequest =
    FriendRequest(id, senderUserId, receiverUserId, status, createdAt, respondedAt, canceledAt)

fun jp.xhw.mikke.api.friendship.application.Friendship.toGraphQl(): Friendship =
    Friendship(id, userLowId, userHighId, status, createdAt, removedAt)

fun jp.xhw.mikke.api.friendship.application.BlockRelation.toGraphQl(): BlockRelation =
    BlockRelation(blockerUserId, blockedUserId, createdAt)

fun jp.xhw.mikke.api.friendship.application.FriendshipSummary.toGraphQl(): FriendshipSummary =
    FriendshipSummary(targetUserId, relationStatus, canViewPosts, canSendRequest)
