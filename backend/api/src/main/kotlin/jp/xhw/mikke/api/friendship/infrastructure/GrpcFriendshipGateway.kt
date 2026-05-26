package jp.xhw.mikke.api.friendship.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.common.infrastructure.call
import jp.xhw.mikke.api.common.infrastructure.toPageInfo
import jp.xhw.mikke.api.common.infrastructure.toProto
import jp.xhw.mikke.api.friendship.application.*
import jp.xhw.mikke.api.friendship.application.BlockRelation
import jp.xhw.mikke.api.friendship.application.FriendRequest
import jp.xhw.mikke.api.friendship.application.Friendship
import jp.xhw.mikke.api.friendship.application.FriendshipSummary
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.infrastructure.authHeaderInterceptor
import jp.xhw.mikke.api.infrastructure.closeChannel
import jp.xhw.mikke.api.infrastructure.gatewayChannelFromEnvironment
import jp.xhw.mikke.api.infrastructure.toIsoString
import jp.xhw.mikke.friendship.v1.*
import jp.xhw.mikke.friendship.v1.BlockRelation as ProtoBlockRelation
import jp.xhw.mikke.friendship.v1.FriendRequest as ProtoFriendRequest
import jp.xhw.mikke.friendship.v1.Friendship as ProtoFriendship
import jp.xhw.mikke.friendship.v1.FriendshipSummary as ProtoFriendshipSummary

class GrpcFriendshipGateway(
    private val channel: ManagedChannel,
    private val stub: FriendshipServiceGrpcKt.FriendshipServiceCoroutineStub =
        FriendshipServiceGrpcKt.FriendshipServiceCoroutineStub(channel),
) : FriendshipGateway {
    override suspend fun sendRequest(
        context: ApiRequestContext,
        receiverUserId: String,
    ): FriendRequest =
        call {
            context
                .stub()
                .sendFriendRequest(SendFriendRequestRequest.newBuilder().setReceiverUserId(receiverUserId).build())
                .friendRequest
                .toFriendRequest()
        }

    override suspend fun acceptRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): Friendship =
        call {
            context
                .stub()
                .acceptFriendRequest(
                    AcceptFriendRequestRequest.newBuilder().setFriendRequestId(friendRequestId).build(),
                ).friendship
                .toFriendship()
        }

    override suspend fun rejectRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest =
        call {
            context
                .stub()
                .rejectFriendRequest(
                    RejectFriendRequestRequest.newBuilder().setFriendRequestId(friendRequestId).build(),
                ).friendRequest
                .toFriendRequest()
        }

    override suspend fun cancelRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest =
        call {
            context
                .stub()
                .cancelFriendRequest(
                    CancelFriendRequestRequest.newBuilder().setFriendRequestId(friendRequestId).build(),
                ).friendRequest
                .toFriendRequest()
        }

    override suspend fun removeFriend(
        context: ApiRequestContext,
        friendUserId: String,
    ) {
        call {
            context.stub().removeFriend(RemoveFriendRequest.newBuilder().setFriendUserId(friendUserId).build())
        }
    }

    override suspend fun blockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ): BlockRelation =
        call {
            context
                .stub()
                .blockUser(BlockUserRequest.newBuilder().setBlockedUserId(blockedUserId).build())
                .blockRelation
                .toBlockRelation()
        }

    override suspend fun unblockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ) {
        call {
            context.stub().unblockUser(UnblockUserRequest.newBuilder().setBlockedUserId(blockedUserId).build())
        }
    }

    override suspend fun getFriendship(
        context: ApiRequestContext,
        targetUserId: String,
    ): FriendshipSummary =
        call {
            context
                .stub()
                .getFriendship(GetFriendshipRequest.newBuilder().setTargetUserId(targetUserId).build())
                .summary
                .toFriendshipSummary()
        }

    override suspend fun listFriends(
        context: ApiRequestContext,
        targetUserId: String,
        page: PageInput,
    ): PageResult<String> =
        call {
            val response =
                context
                    .stub()
                    .listFriends(
                        ListFriendsRequest
                            .newBuilder()
                            .setTargetUserId(targetUserId)
                            .setPage(page.toProto())
                            .build(),
                    )
            PageResult(response.friendUserIdsList, response.pageInfo.toPageInfo())
        }

    override suspend fun incomingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest> =
        call {
            val response =
                context.stub().listIncomingFriendRequests(
                    ListIncomingFriendRequestsRequest.newBuilder().setPage(page.toProto()).build(),
                )
            PageResult(response.friendRequestsList.map { it.toFriendRequest() }, response.pageInfo.toPageInfo())
        }

    override suspend fun outgoingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest> =
        call {
            val response =
                context.stub().listOutgoingFriendRequests(
                    ListOutgoingFriendRequestsRequest.newBuilder().setPage(page.toProto()).build(),
                )
            PageResult(response.friendRequestsList.map { it.toFriendRequest() }, response.pageInfo.toPageInfo())
        }

    override fun close() = closeChannel(channel)

    private fun ApiRequestContext.stub(): FriendshipServiceGrpcKt.FriendshipServiceCoroutineStub =
        authHeaderInterceptor(this)?.let { stub.withInterceptors(it) } ?: stub

    companion object {
        fun fromEnvironment(): GrpcFriendshipGateway =
            GrpcFriendshipGateway(
                gatewayChannelFromEnvironment(
                    targetEnv = "FRIENDSHIP_SERVICE_TARGET",
                    hostEnv = "FRIENDSHIP_SERVICE_HOST",
                    portEnv = "FRIENDSHIP_SERVICE_PORT",
                    defaultPort = 50054,
                ),
            )
    }
}

private fun ProtoFriendRequest.toFriendRequest(): FriendRequest =
    FriendRequest(
        id = id,
        senderUserId = senderUserId,
        receiverUserId = receiverUserId,
        status = status.name.removePrefix("FRIEND_REQUEST_STATUS_"),
        createdAt = createdAt.toIsoString(),
        respondedAt = if (hasRespondedAt()) respondedAt.toIsoString() else null,
        canceledAt = if (hasCanceledAt()) canceledAt.toIsoString() else null,
    )

private fun ProtoFriendship.toFriendship(): Friendship =
    Friendship(
        id = id,
        userLowId = userLowId,
        userHighId = userHighId,
        status = status.name.removePrefix("FRIENDSHIP_STATUS_"),
        createdAt = createdAt.toIsoString(),
        removedAt = if (hasRemovedAt()) removedAt.toIsoString() else null,
    )

private fun ProtoBlockRelation.toBlockRelation(): BlockRelation =
    BlockRelation(
        blockerUserId = blockerUserId,
        blockedUserId = blockedUserId,
        createdAt = createdAt.toIsoString(),
    )

private fun ProtoFriendshipSummary.toFriendshipSummary(): FriendshipSummary =
    FriendshipSummary(
        targetUserId = targetUserId,
        relationStatus = relationStatus.name.removePrefix("FRIENDSHIP_RELATION_STATUS_"),
        canViewPosts = canViewPosts,
        canSendRequest = canSendRequest,
    )
