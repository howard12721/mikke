package jp.xhw.mikke.services.friendship

import io.grpc.Status
import jp.xhw.mikke.friendship.v1.*
import jp.xhw.mikke.platform.grpc.requireInternalCaller
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.friendship.application.service.FriendshipService
import jp.xhw.mikke.services.friendship.model.UserId
import jp.xhw.mikke.services.friendship.model.parseFriendRequestId
import jp.xhw.mikke.services.friendship.model.parseUserId
import jp.xhw.mikke.platform.grpc.currentAuthenticatedUser as requireAuthenticatedUserUuid

private val INTERNAL_VIEWER_CALLERS = setOf("post-service", "media-service", "api")

class FriendshipServiceRpc(
    private val friendshipService: FriendshipService,
) : FriendshipServiceGrpcKt.FriendshipServiceCoroutineImplBase() {
    override suspend fun sendFriendRequest(request: SendFriendRequestRequest): SendFriendRequestResponse {
        val senderUserId = currentAuthenticatedUser()
        val receiverUserId = parseUserId(request.receiverUserId.requireField("receiver_user_id"))
        val friendRequest = friendshipService.sendFriendRequest(senderUserId, receiverUserId)

        return SendFriendRequestResponse
            .newBuilder()
            .setFriendRequest(friendRequest.toProto())
            .build()
    }

    override suspend fun acceptFriendRequest(request: AcceptFriendRequestRequest): AcceptFriendRequestResponse {
        val receiverUserId = currentAuthenticatedUser()
        val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
        val friendship = friendshipService.acceptFriendRequest(receiverUserId, friendRequestId)

        return AcceptFriendRequestResponse
            .newBuilder()
            .setFriendship(friendship.toProto())
            .build()
    }

    override suspend fun rejectFriendRequest(request: RejectFriendRequestRequest): RejectFriendRequestResponse {
        val receiverUserId = currentAuthenticatedUser()
        val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
        val friendRequest = friendshipService.rejectFriendRequest(receiverUserId, friendRequestId)

        return RejectFriendRequestResponse
            .newBuilder()
            .setFriendRequest(friendRequest.toProto())
            .build()
    }

    override suspend fun cancelFriendRequest(request: CancelFriendRequestRequest): CancelFriendRequestResponse {
        val senderUserId = currentAuthenticatedUser()
        val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
        val friendRequest = friendshipService.cancelFriendRequest(senderUserId, friendRequestId)

        return CancelFriendRequestResponse
            .newBuilder()
            .setFriendRequest(friendRequest.toProto())
            .build()
    }

    override suspend fun removeFriend(request: RemoveFriendRequest): RemoveFriendResponse {
        val actorUserId = currentAuthenticatedUser()
        val friendUserId = parseUserId(request.friendUserId.requireField("friend_user_id"))
        friendshipService.removeFriend(actorUserId, friendUserId)

        return RemoveFriendResponse.getDefaultInstance()
    }

    override suspend fun blockUser(request: BlockUserRequest): BlockUserResponse {
        val blockerUserId = currentAuthenticatedUser()
        val blockedUserId = parseUserId(request.blockedUserId.requireField("blocked_user_id"))
        val blockRelation = friendshipService.blockUser(blockerUserId, blockedUserId)

        return BlockUserResponse
            .newBuilder()
            .setBlockRelation(blockRelation.toProto())
            .build()
    }

    override suspend fun unblockUser(request: UnblockUserRequest): UnblockUserResponse {
        val blockerUserId = currentAuthenticatedUser()
        val blockedUserId = parseUserId(request.blockedUserId.requireField("blocked_user_id"))
        friendshipService.unblockUser(blockerUserId, blockedUserId)

        return UnblockUserResponse.getDefaultInstance()
    }

    override suspend fun getFriendship(request: GetFriendshipRequest): GetFriendshipResponse {
        val viewerUserId = currentAuthenticatedUser()
        val targetUserId = parseUserId(request.targetUserId.requireField("target_user_id"))
        val summary = friendshipService.getFriendshipSummary(viewerUserId, targetUserId)

        return GetFriendshipResponse
            .newBuilder()
            .setSummary(summary.toProto())
            .build()
    }

    override suspend fun listFriends(request: ListFriendsRequest): ListFriendsResponse {
        val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
        val targetUserId = parseUserId(request.targetUserId.requireField("target_user_id"))
        val result = friendshipService.listFriends(targetUserId, page)

        return ListFriendsResponse
            .newBuilder()
            .addAllFriendUserIds(result.items.map { formatUserId(it) })
            .setPageInfo(result.toPageInfo())
            .build()
    }

    override suspend fun listIncomingFriendRequests(request: ListIncomingFriendRequestsRequest): ListIncomingFriendRequestsResponse {
        val receiverUserId = currentAuthenticatedUser()
        val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
        val result = friendshipService.listIncomingFriendRequests(receiverUserId, page)

        return ListIncomingFriendRequestsResponse
            .newBuilder()
            .addAllFriendRequests(result.items.map { it.toProto() })
            .setPageInfo(result.toPageInfo())
            .build()
    }

    override suspend fun listOutgoingFriendRequests(request: ListOutgoingFriendRequestsRequest): ListOutgoingFriendRequestsResponse {
        val senderUserId = currentAuthenticatedUser()
        val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
        val result = friendshipService.listOutgoingFriendRequests(senderUserId, page)

        return ListOutgoingFriendRequestsResponse
            .newBuilder()
            .addAllFriendRequests(result.items.map { it.toProto() })
            .setPageInfo(result.toPageInfo())
            .build()
    }

    override suspend fun batchGetFriendshipSummaries(request: BatchGetFriendshipSummariesRequest): BatchGetFriendshipSummariesResponse {
        val viewerUserId = currentAuthenticatedUser()
        val targetUserIds = request.targetUserIdsList.map { parseUserId(it.requireField("target_user_id")) }
        val summaries = friendshipService.batchGetFriendshipSummaries(viewerUserId, targetUserIds)

        return BatchGetFriendshipSummariesResponse
            .newBuilder()
            .addAllSummaries(summaries.map { it.toProto() })
            .build()
    }

    override suspend fun checkCanViewUserPosts(request: CheckCanViewUserPostsRequest): CheckCanViewUserPostsResponse {
        val viewerUserId = currentAuthenticatedUser()
        val ownerUserId = parseUserId(request.ownerUserId.requireField("owner_user_id"))
        val visibility = friendshipService.checkCanViewUserPosts(viewerUserId, ownerUserId)

        return CheckCanViewUserPostsResponse
            .newBuilder()
            .setCanView(visibility.canView)
            .setRelationStatus(visibility.relationStatus.toProto())
            .build()
    }

    override suspend fun checkCanViewUserPostsForViewer(
        request: CheckCanViewUserPostsForViewerRequest,
    ): CheckCanViewUserPostsForViewerResponse {
        requireInternalCaller(INTERNAL_VIEWER_CALLERS)
        val viewerUserId = parseUserId(request.viewerUserId.requireField("viewer_user_id"))
        val ownerUserId = parseUserId(request.ownerUserId.requireField("owner_user_id"))
        val visibility = friendshipService.checkCanViewUserPosts(viewerUserId, ownerUserId)

        return CheckCanViewUserPostsForViewerResponse
            .newBuilder()
            .setCanView(visibility.canView)
            .setRelationStatus(visibility.relationStatus.toProto())
            .build()
    }
}

private fun formatUserId(userId: UserId): String = formatGrpcUuid(userId.value)

private fun String.requireField(fieldName: String): String =
    trim().takeIf { it.isNotEmpty() }
        ?: throw Status.INVALID_ARGUMENT.withDescription("$fieldName is required").asRuntimeException()

private fun currentAuthenticatedUser(): UserId = UserId(requireAuthenticatedUserUuid())
