package jp.xhw.mikke.services.friendship

import jp.xhw.mikke.common.v1.PageInfo
import jp.xhw.mikke.platform.pagination.PageSlice
import jp.xhw.mikke.platform.time.toProtoTimestamp
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.friendship.model.BlockRelation as DomainBlockRelation
import jp.xhw.mikke.services.friendship.model.FriendRequest as DomainFriendRequest
import jp.xhw.mikke.services.friendship.model.FriendRequestStatus as DomainFriendRequestStatus
import jp.xhw.mikke.services.friendship.model.Friendship as DomainFriendship
import jp.xhw.mikke.services.friendship.model.FriendshipRelationStatus as DomainFriendshipRelationStatus
import jp.xhw.mikke.services.friendship.model.FriendshipStatus as DomainFriendshipStatus
import jp.xhw.mikke.services.friendship.model.FriendshipSummary as DomainFriendshipSummary

fun DomainFriendRequest.toProto(): jp.xhw.mikke.friendship.v1.FriendRequest {
    val request = this
    return jp.xhw.mikke.friendship.v1.FriendRequest
        .newBuilder()
        .setId(formatGrpcUuid(request.id.value))
        .setSenderUserId(formatGrpcUuid(request.senderUserId.value))
        .setReceiverUserId(formatGrpcUuid(request.receiverUserId.value))
        .setStatus(request.status.toProto())
        .setCreatedAt(request.createdAt.toProtoTimestamp())
        .apply {
            request.respondedAt?.let { setRespondedAt(it.toProtoTimestamp()) }
            request.canceledAt?.let { setCanceledAt(it.toProtoTimestamp()) }
        }.build()
}

fun DomainFriendship.toProto(): jp.xhw.mikke.friendship.v1.Friendship {
    val friendship = this
    return jp.xhw.mikke.friendship.v1.Friendship
        .newBuilder()
        .setId(formatGrpcUuid(friendship.id.value))
        .setUserLowId(formatGrpcUuid(friendship.userLowId.value))
        .setUserHighId(formatGrpcUuid(friendship.userHighId.value))
        .setStatus(friendship.status.toProto())
        .setCreatedAt(friendship.createdAt.toProtoTimestamp())
        .apply {
            friendship.removedAt?.let { setRemovedAt(it.toProtoTimestamp()) }
        }.build()
}

fun DomainBlockRelation.toProto(): jp.xhw.mikke.friendship.v1.BlockRelation =
    jp.xhw.mikke.friendship.v1.BlockRelation
        .newBuilder()
        .setBlockerUserId(formatGrpcUuid(blockerUserId.value))
        .setBlockedUserId(formatGrpcUuid(blockedUserId.value))
        .setCreatedAt(createdAt.toProtoTimestamp())
        .build()

fun DomainFriendshipSummary.toProto(): jp.xhw.mikke.friendship.v1.FriendshipSummary =
    jp.xhw.mikke.friendship.v1.FriendshipSummary
        .newBuilder()
        .setTargetUserId(formatGrpcUuid(targetUserId.value))
        .setRelationStatus(relationStatus.toProto())
        .setCanViewPosts(canViewPosts)
        .setCanSendRequest(canSendRequest)
        .build()

fun PageSlice<*>.toPageInfo(): PageInfo =
    PageInfo
        .newBuilder()
        .setHasNextPage(hasNextPage)
        .apply {
            nextPageToken?.let { setNextPageToken(it) }
        }.build()

fun DomainFriendshipRelationStatus.toProto(): jp.xhw.mikke.friendship.v1.FriendshipRelationStatus =
    when (this) {
        DomainFriendshipRelationStatus.NONE -> {
            jp.xhw.mikke.friendship.v1.FriendshipRelationStatus.FRIENDSHIP_RELATION_STATUS_NONE
        }

        DomainFriendshipRelationStatus.FRIENDS -> {
            jp.xhw.mikke.friendship.v1.FriendshipRelationStatus.FRIENDSHIP_RELATION_STATUS_FRIENDS
        }

        DomainFriendshipRelationStatus.REQUEST_SENT -> {
            jp.xhw.mikke.friendship.v1.FriendshipRelationStatus.FRIENDSHIP_RELATION_STATUS_REQUEST_SENT
        }

        DomainFriendshipRelationStatus.REQUEST_RECEIVED -> {
            jp.xhw.mikke.friendship.v1.FriendshipRelationStatus.FRIENDSHIP_RELATION_STATUS_REQUEST_RECEIVED
        }

        DomainFriendshipRelationStatus.BLOCKED_BY_ME -> {
            jp.xhw.mikke.friendship.v1.FriendshipRelationStatus.FRIENDSHIP_RELATION_STATUS_BLOCKED_BY_ME
        }

        DomainFriendshipRelationStatus.BLOCKED_ME -> {
            jp.xhw.mikke.friendship.v1.FriendshipRelationStatus.FRIENDSHIP_RELATION_STATUS_BLOCKED_ME
        }
    }

private fun DomainFriendRequestStatus.toProto(): jp.xhw.mikke.friendship.v1.FriendRequestStatus =
    when (this) {
        DomainFriendRequestStatus.PENDING -> {
            jp.xhw.mikke.friendship.v1.FriendRequestStatus.FRIEND_REQUEST_STATUS_PENDING
        }

        DomainFriendRequestStatus.ACCEPTED -> {
            jp.xhw.mikke.friendship.v1.FriendRequestStatus.FRIEND_REQUEST_STATUS_ACCEPTED
        }

        DomainFriendRequestStatus.REJECTED -> {
            jp.xhw.mikke.friendship.v1.FriendRequestStatus.FRIEND_REQUEST_STATUS_REJECTED
        }

        DomainFriendRequestStatus.CANCELED -> {
            jp.xhw.mikke.friendship.v1.FriendRequestStatus.FRIEND_REQUEST_STATUS_CANCELED
        }
    }

private fun DomainFriendshipStatus.toProto(): jp.xhw.mikke.friendship.v1.FriendshipStatus =
    when (this) {
        DomainFriendshipStatus.ACTIVE -> {
            jp.xhw.mikke.friendship.v1.FriendshipStatus.FRIENDSHIP_STATUS_ACTIVE
        }

        DomainFriendshipStatus.REMOVED -> {
            jp.xhw.mikke.friendship.v1.FriendshipStatus.FRIENDSHIP_STATUS_REMOVED
        }
    }
