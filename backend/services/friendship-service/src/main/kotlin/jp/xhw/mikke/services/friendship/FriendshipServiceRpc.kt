package jp.xhw.mikke.services.friendship

import io.grpc.Status
import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.friendship.v1.*
import jp.xhw.mikke.platform.grpc.requireInternalCaller
import jp.xhw.mikke.platform.grpc.requireUserUuid
import jp.xhw.mikke.platform.grpc.withGrpcExceptionMapping
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.friendship.application.exception.FriendshipApplicationException
import jp.xhw.mikke.services.friendship.application.service.FriendshipService
import jp.xhw.mikke.services.friendship.model.UserId
import jp.xhw.mikke.services.friendship.model.parseFriendRequestId
import jp.xhw.mikke.services.friendship.model.parseUserId
import java.util.logging.Logger

private val INTERNAL_VIEWER_CALLERS = setOf("post-service", "media-service", "api")
private val logger: Logger = Logger.getLogger("friendship-service")

class FriendshipServiceRpc(
    private val friendshipService: FriendshipService,
) : FriendshipServiceGrpcKt.FriendshipServiceCoroutineImplBase() {
    override suspend fun sendFriendRequest(request: SendFriendRequestRequest): SendFriendRequestResponse =
        mapRpcExceptions {
            val senderUserId = request.actor.toUserId()
            val receiverUserId = parseUserId(request.receiverUserId.requireField("receiver_user_id"))
            val friendRequest = friendshipService.sendFriendRequest(senderUserId, receiverUserId)

            SendFriendRequestResponse
                .newBuilder()
                .setFriendRequest(friendRequest.toProto())
                .build()
        }

    override suspend fun acceptFriendRequest(request: AcceptFriendRequestRequest): AcceptFriendRequestResponse =
        mapRpcExceptions {
            val receiverUserId = request.actor.toUserId()
            val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
            val friendship = friendshipService.acceptFriendRequest(receiverUserId, friendRequestId)

            AcceptFriendRequestResponse
                .newBuilder()
                .setFriendship(friendship.toProto())
                .build()
        }

    override suspend fun rejectFriendRequest(request: RejectFriendRequestRequest): RejectFriendRequestResponse =
        mapRpcExceptions {
            val receiverUserId = request.actor.toUserId()
            val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
            val friendRequest = friendshipService.rejectFriendRequest(receiverUserId, friendRequestId)

            RejectFriendRequestResponse
                .newBuilder()
                .setFriendRequest(friendRequest.toProto())
                .build()
        }

    override suspend fun cancelFriendRequest(request: CancelFriendRequestRequest): CancelFriendRequestResponse =
        mapRpcExceptions {
            val senderUserId = request.actor.toUserId()
            val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
            val friendRequest = friendshipService.cancelFriendRequest(senderUserId, friendRequestId)

            CancelFriendRequestResponse
                .newBuilder()
                .setFriendRequest(friendRequest.toProto())
                .build()
        }

    override suspend fun removeFriend(request: RemoveFriendRequest): RemoveFriendResponse =
        mapRpcExceptions {
            val actorUserId = request.actor.toUserId()
            val friendUserId = parseUserId(request.friendUserId.requireField("friend_user_id"))
            friendshipService.removeFriend(actorUserId, friendUserId)

            RemoveFriendResponse.getDefaultInstance()
        }

    override suspend fun blockUser(request: BlockUserRequest): BlockUserResponse =
        mapRpcExceptions {
            val blockerUserId = request.actor.toUserId()
            val blockedUserId = parseUserId(request.blockedUserId.requireField("blocked_user_id"))
            val blockRelation = friendshipService.blockUser(blockerUserId, blockedUserId)

            BlockUserResponse
                .newBuilder()
                .setBlockRelation(blockRelation.toProto())
                .build()
        }

    override suspend fun unblockUser(request: UnblockUserRequest): UnblockUserResponse =
        mapRpcExceptions {
            val blockerUserId = request.actor.toUserId()
            val blockedUserId = parseUserId(request.blockedUserId.requireField("blocked_user_id"))
            friendshipService.unblockUser(blockerUserId, blockedUserId)

            UnblockUserResponse.getDefaultInstance()
        }

    override suspend fun getFriendship(request: GetFriendshipRequest): GetFriendshipResponse =
        mapRpcExceptions {
            val viewerUserId = request.actor.toUserId()
            val targetUserId = parseUserId(request.targetUserId.requireField("target_user_id"))
            val summary = friendshipService.getFriendshipSummary(viewerUserId, targetUserId)

            GetFriendshipResponse
                .newBuilder()
                .setSummary(summary.toProto())
                .build()
        }

    override suspend fun listFriends(request: ListFriendsRequest): ListFriendsResponse =
        mapRpcExceptions {
            val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
            val targetUserId = parseUserId(request.targetUserId.requireField("target_user_id"))
            val result = friendshipService.listFriends(targetUserId, page)

            ListFriendsResponse
                .newBuilder()
                .addAllFriendUserIds(result.items.map { formatUserId(it) })
                .setPageInfo(result.toPageInfo())
                .build()
        }

    override suspend fun listIncomingFriendRequests(request: ListIncomingFriendRequestsRequest): ListIncomingFriendRequestsResponse =
        mapRpcExceptions {
            val receiverUserId = request.actor.toUserId()
            val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
            val result = friendshipService.listIncomingFriendRequests(receiverUserId, page)

            ListIncomingFriendRequestsResponse
                .newBuilder()
                .addAllFriendRequests(result.items.map { it.toProto() })
                .setPageInfo(result.toPageInfo())
                .build()
        }

    override suspend fun listOutgoingFriendRequests(request: ListOutgoingFriendRequestsRequest): ListOutgoingFriendRequestsResponse =
        mapRpcExceptions {
            val senderUserId = request.actor.toUserId()
            val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
            val result = friendshipService.listOutgoingFriendRequests(senderUserId, page)

            ListOutgoingFriendRequestsResponse
                .newBuilder()
                .addAllFriendRequests(result.items.map { it.toProto() })
                .setPageInfo(result.toPageInfo())
                .build()
        }

    override suspend fun batchGetFriendshipSummaries(request: BatchGetFriendshipSummariesRequest): BatchGetFriendshipSummariesResponse =
        mapRpcExceptions {
            val viewerUserId = request.actor.toUserId()
            val targetUserIds = request.targetUserIdsList.map { parseUserId(it.requireField("target_user_id")) }
            val summaries = friendshipService.batchGetFriendshipSummaries(viewerUserId, targetUserIds)

            BatchGetFriendshipSummariesResponse
                .newBuilder()
                .addAllSummaries(summaries.map { it.toProto() })
                .build()
        }

    override suspend fun checkCanViewUserPosts(request: CheckCanViewUserPostsRequest): CheckCanViewUserPostsResponse =
        mapRpcExceptions {
            requireInternalCaller(INTERNAL_VIEWER_CALLERS)
            val viewerUserId = parseUserId(request.viewerUserId.requireField("viewer_user_id"))
            val ownerUserId = parseUserId(request.ownerUserId.requireField("owner_user_id"))
            val visibility = friendshipService.checkCanViewUserPosts(viewerUserId, ownerUserId)

            CheckCanViewUserPostsResponse
                .newBuilder()
                .setCanView(visibility.canView)
                .setRelationStatus(visibility.relationStatus.toProto())
                .build()
        }

    override suspend fun checkCanViewUserPostsForViewer(
        request: CheckCanViewUserPostsForViewerRequest,
    ): CheckCanViewUserPostsForViewerResponse =
        mapRpcExceptions {
            requireInternalCaller(INTERNAL_VIEWER_CALLERS)
            val viewerUserId = parseUserId(request.viewerUserId.requireField("viewer_user_id"))
            val ownerUserId = parseUserId(request.ownerUserId.requireField("owner_user_id"))
            val visibility = friendshipService.checkCanViewUserPosts(viewerUserId, ownerUserId)

            CheckCanViewUserPostsForViewerResponse
                .newBuilder()
                .setCanView(visibility.canView)
                .setRelationStatus(visibility.relationStatus.toProto())
                .build()
        }
}

private suspend inline fun <T> mapRpcExceptions(block: suspend () -> T): T =
    withGrpcExceptionMapping(
        logger = logger,
        serviceName = "friendship-service",
        internalErrorDescription = "Internal friendship service error",
        domainExceptionMapper = { candidate ->
            (candidate as? FriendshipApplicationException)?.toGrpcStatus()
        },
        block = block,
    )

private fun ActorContext.toUserId(): UserId = UserId(requireUserUuid())

private fun formatUserId(userId: UserId): String = formatGrpcUuid(userId.value)

private fun String.requireField(fieldName: String): String =
    trim().takeIf { it.isNotEmpty() }
        ?: throw Status.INVALID_ARGUMENT.withDescription("$fieldName is required").asRuntimeException()
