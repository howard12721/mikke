package jp.xhw.mikke.api.friendship.application

import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.common.application.normalized
import jp.xhw.mikke.api.common.application.requireText
import jp.xhw.mikke.api.graphql.ApiRequestContext

class FriendshipApiService(
    private val friendshipGateway: FriendshipGateway,
) {
    suspend fun sendRequest(
        context: ApiRequestContext,
        receiverUserId: String,
    ): FriendRequest = friendshipGateway.sendRequest(context, receiverUserId.requireText("receiverUserId"))

    suspend fun acceptRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): Friendship = friendshipGateway.acceptRequest(context, friendRequestId.requireText("friendRequestId"))

    suspend fun rejectRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest = friendshipGateway.rejectRequest(context, friendRequestId.requireText("friendRequestId"))

    suspend fun cancelRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest = friendshipGateway.cancelRequest(context, friendRequestId.requireText("friendRequestId"))

    suspend fun removeFriend(
        context: ApiRequestContext,
        friendUserId: String,
    ) = friendshipGateway.removeFriend(context, friendUserId.requireText("friendUserId"))

    suspend fun blockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ): BlockRelation = friendshipGateway.blockUser(context, blockedUserId.requireText("blockedUserId"))

    suspend fun unblockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ) = friendshipGateway.unblockUser(context, blockedUserId.requireText("blockedUserId"))

    suspend fun getFriendship(
        context: ApiRequestContext,
        targetUserId: String,
    ): FriendshipSummary = friendshipGateway.getFriendship(context, targetUserId.requireText("targetUserId"))

    suspend fun listFriends(
        context: ApiRequestContext,
        targetUserId: String,
        page: PageInput,
    ): PageResult<String> = friendshipGateway.listFriends(context, targetUserId.requireText("targetUserId"), page.normalized())

    suspend fun incomingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest> = friendshipGateway.incomingRequests(context, page.normalized())

    suspend fun outgoingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest> = friendshipGateway.outgoingRequests(context, page.normalized())
}
