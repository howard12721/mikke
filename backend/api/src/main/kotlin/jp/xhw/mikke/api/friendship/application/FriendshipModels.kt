package jp.xhw.mikke.api.friendship.application

import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.graphql.ApiRequestContext

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

interface FriendshipGateway : AutoCloseable {
    suspend fun sendRequest(
        context: ApiRequestContext,
        receiverUserId: String,
    ): FriendRequest

    suspend fun acceptRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): Friendship

    suspend fun rejectRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest

    suspend fun cancelRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest

    suspend fun removeFriend(
        context: ApiRequestContext,
        friendUserId: String,
    )

    suspend fun blockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ): BlockRelation

    suspend fun unblockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    )

    suspend fun getFriendship(
        context: ApiRequestContext,
        targetUserId: String,
    ): FriendshipSummary

    suspend fun listFriends(
        context: ApiRequestContext,
        targetUserId: String,
        page: PageInput,
    ): PageResult<String>

    suspend fun incomingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest>

    suspend fun outgoingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest>
}
