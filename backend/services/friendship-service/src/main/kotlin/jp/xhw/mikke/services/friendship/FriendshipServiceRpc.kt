package jp.xhw.mikke.services.friendship

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import jp.xhw.mikke.friendship.v1.*
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.grpc.requireInternalCaller
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.friendship.application.exception.BlockRelationNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.DuplicateFriendRequestException
import jp.xhw.mikke.services.friendship.application.exception.FriendRequestNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipApplicationException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipNotAllowedException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipStateException
import jp.xhw.mikke.services.friendship.application.exception.InvalidFriendshipInputException
import jp.xhw.mikke.services.friendship.application.service.FriendshipService
import jp.xhw.mikke.services.friendship.model.UserId
import jp.xhw.mikke.services.friendship.model.parseFriendRequestId
import jp.xhw.mikke.services.friendship.model.parseUserId
import java.util.logging.Level
import java.util.logging.Logger
import jp.xhw.mikke.platform.grpc.currentAuthenticatedUser as requireAuthenticatedUserUuid

private val logger: Logger = Logger.getLogger(FriendshipServiceRpc::class.java.name)
private val INTERNAL_VIEWER_CALLERS = setOf("post-service", "media-service", "api")

class FriendshipServiceRpc(
    private val friendshipService: FriendshipService,
) : FriendshipServiceGrpcKt.FriendshipServiceCoroutineImplBase() {
    override suspend fun sendFriendRequest(request: SendFriendRequestRequest): SendFriendRequestResponse {
        val friendRequest =
            execute {
                val senderUserId = currentAuthenticatedUser()
                val receiverUserId = parseUserId(request.receiverUserId.requireField("receiver_user_id"))
                friendshipService.sendFriendRequest(senderUserId, receiverUserId)
            }

        return SendFriendRequestResponse
            .newBuilder()
            .setFriendRequest(friendRequest.toProto())
            .build()
    }

    override suspend fun acceptFriendRequest(request: AcceptFriendRequestRequest): AcceptFriendRequestResponse {
        val friendship =
            execute {
                val receiverUserId = currentAuthenticatedUser()
                val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
                friendshipService.acceptFriendRequest(receiverUserId, friendRequestId)
            }

        return AcceptFriendRequestResponse
            .newBuilder()
            .setFriendship(friendship.toProto())
            .build()
    }

    override suspend fun rejectFriendRequest(request: RejectFriendRequestRequest): RejectFriendRequestResponse {
        val friendRequest =
            execute {
                val receiverUserId = currentAuthenticatedUser()
                val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
                friendshipService.rejectFriendRequest(receiverUserId, friendRequestId)
            }

        return RejectFriendRequestResponse
            .newBuilder()
            .setFriendRequest(friendRequest.toProto())
            .build()
    }

    override suspend fun cancelFriendRequest(request: CancelFriendRequestRequest): CancelFriendRequestResponse {
        val friendRequest =
            execute {
                val senderUserId = currentAuthenticatedUser()
                val friendRequestId = parseFriendRequestId(request.friendRequestId.requireField("friend_request_id"))
                friendshipService.cancelFriendRequest(senderUserId, friendRequestId)
            }

        return CancelFriendRequestResponse
            .newBuilder()
            .setFriendRequest(friendRequest.toProto())
            .build()
    }

    override suspend fun removeFriend(request: RemoveFriendRequest): RemoveFriendResponse {
        execute {
            val actorUserId = currentAuthenticatedUser()
            val friendUserId = parseUserId(request.friendUserId.requireField("friend_user_id"))
            friendshipService.removeFriend(actorUserId, friendUserId)
        }

        return RemoveFriendResponse.getDefaultInstance()
    }

    override suspend fun blockUser(request: BlockUserRequest): BlockUserResponse {
        val blockRelation =
            execute {
                val blockerUserId = currentAuthenticatedUser()
                val blockedUserId = parseUserId(request.blockedUserId.requireField("blocked_user_id"))
                friendshipService.blockUser(blockerUserId, blockedUserId)
            }

        return BlockUserResponse
            .newBuilder()
            .setBlockRelation(blockRelation.toProto())
            .build()
    }

    override suspend fun unblockUser(request: UnblockUserRequest): UnblockUserResponse {
        execute {
            val blockerUserId = currentAuthenticatedUser()
            val blockedUserId = parseUserId(request.blockedUserId.requireField("blocked_user_id"))
            friendshipService.unblockUser(blockerUserId, blockedUserId)
        }

        return UnblockUserResponse.getDefaultInstance()
    }

    override suspend fun getFriendship(request: GetFriendshipRequest): GetFriendshipResponse {
        val summary =
            execute {
                val viewerUserId = currentAuthenticatedUser()
                val targetUserId = parseUserId(request.targetUserId.requireField("target_user_id"))
                friendshipService.getFriendshipSummary(viewerUserId, targetUserId)
            }

        return GetFriendshipResponse
            .newBuilder()
            .setSummary(summary.toProto())
            .build()
    }

    override suspend fun listFriends(request: ListFriendsRequest): ListFriendsResponse {
        val result =
            execute {
                val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
                val targetUserId = parseUserId(request.targetUserId.requireField("target_user_id"))
                friendshipService.listFriends(targetUserId, page)
            }

        return ListFriendsResponse
            .newBuilder()
            .addAllFriendUserIds(result.items.map { formatUserId(it) })
            .setPageInfo(result.toPageInfo())
            .build()
    }

    override suspend fun listIncomingFriendRequests(request: ListIncomingFriendRequestsRequest): ListIncomingFriendRequestsResponse {
        val result =
            execute {
                val receiverUserId = currentAuthenticatedUser()
                val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
                friendshipService.listIncomingFriendRequests(receiverUserId, page)
            }

        return ListIncomingFriendRequestsResponse
            .newBuilder()
            .addAllFriendRequests(result.items.map { it.toProto() })
            .setPageInfo(result.toPageInfo())
            .build()
    }

    override suspend fun listOutgoingFriendRequests(request: ListOutgoingFriendRequestsRequest): ListOutgoingFriendRequestsResponse {
        val result =
            execute {
                val senderUserId = currentAuthenticatedUser()
                val page = PageRequestInput(request.page.pageSize, request.page.pageToken).validate()
                friendshipService.listOutgoingFriendRequests(senderUserId, page)
            }

        return ListOutgoingFriendRequestsResponse
            .newBuilder()
            .addAllFriendRequests(result.items.map { it.toProto() })
            .setPageInfo(result.toPageInfo())
            .build()
    }

    override suspend fun batchGetFriendshipSummaries(request: BatchGetFriendshipSummariesRequest): BatchGetFriendshipSummariesResponse {
        val summaries =
            execute {
                val viewerUserId = currentAuthenticatedUser()
                val targetUserIds = request.targetUserIdsList.map { parseUserId(it.requireField("target_user_id")) }
                friendshipService.batchGetFriendshipSummaries(viewerUserId, targetUserIds)
            }

        return BatchGetFriendshipSummariesResponse
            .newBuilder()
            .addAllSummaries(summaries.map { it.toProto() })
            .build()
    }

    override suspend fun checkCanViewUserPosts(request: CheckCanViewUserPostsRequest): CheckCanViewUserPostsResponse {
        val visibility =
            execute {
                val viewerUserId = currentAuthenticatedUser()
                val ownerUserId = parseUserId(request.ownerUserId.requireField("owner_user_id"))
                friendshipService.checkCanViewUserPosts(viewerUserId, ownerUserId)
            }

        return CheckCanViewUserPostsResponse
            .newBuilder()
            .setCanView(visibility.canView)
            .setRelationStatus(visibility.relationStatus.toProto())
            .build()
    }

    override suspend fun checkCanViewUserPostsForViewer(
        request: CheckCanViewUserPostsForViewerRequest,
    ): CheckCanViewUserPostsForViewerResponse {
        val visibility =
            execute {
                requireInternalCaller(INTERNAL_VIEWER_CALLERS)
                val viewerUserId = parseUserId(request.viewerUserId.requireField("viewer_user_id"))
                val ownerUserId = parseUserId(request.ownerUserId.requireField("owner_user_id"))
                friendshipService.checkCanViewUserPosts(viewerUserId, ownerUserId)
            }

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

private inline fun <T> execute(block: () -> T): T =
    try {
        block()
    } catch (e: StatusRuntimeException) {
        throw e
    } catch (e: StatusException) {
        throw e
    } catch (e: FriendshipApplicationException) {
        throw e.toStatus().withCause(e).asRuntimeException()
    } catch (e: ValidationException) {
        throw Status.INVALID_ARGUMENT.withDescription(e.message).withCause(e).asRuntimeException()
    } catch (e: Exception) {
        logger.log(Level.SEVERE, "Unhandled friendship-service RPC exception", e)
        throw Status.INTERNAL
            .withDescription("Internal friendship service error")
            .withCause(e)
            .asRuntimeException()
    }

private fun FriendshipApplicationException.toStatus(): Status =
    when (this) {
        is InvalidFriendshipInputException -> Status.INVALID_ARGUMENT.withDescription(message)
        is DuplicateFriendRequestException -> Status.ALREADY_EXISTS.withDescription(message)
        is FriendRequestNotFoundException -> Status.NOT_FOUND.withDescription(message)
        is FriendshipNotFoundException -> Status.NOT_FOUND.withDescription(message)
        is BlockRelationNotFoundException -> Status.NOT_FOUND.withDescription(message)
        is FriendshipStateException -> Status.FAILED_PRECONDITION.withDescription(message)
        is FriendshipNotAllowedException -> Status.PERMISSION_DENIED.withDescription(message)
    }

private fun currentAuthenticatedUser(): UserId = UserId(requireAuthenticatedUserUuid())
