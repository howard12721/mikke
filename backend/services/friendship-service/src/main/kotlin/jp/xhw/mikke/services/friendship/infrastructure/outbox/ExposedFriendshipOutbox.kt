package jp.xhw.mikke.services.friendship.infrastructure.outbox

import jp.xhw.mikke.events.friendship.FriendshipEventTypes
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.outbox.exposed.OutboxTable
import jp.xhw.mikke.platform.outbox.exposed.insertEntry
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.friendship.application.port.FriendshipOutbox
import jp.xhw.mikke.services.friendship.model.BlockRelation
import jp.xhw.mikke.services.friendship.model.FriendRequest
import jp.xhw.mikke.services.friendship.model.Friendship
import jp.xhw.mikke.services.friendship.model.UserId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ExposedFriendshipOutbox(
    private val clock: Clock = Clock.System,
) : FriendshipOutbox {
    override fun appendFriendRequestRequested(request: FriendRequest) {
        insert(
            eventType = FriendshipEventTypes.REQUESTED,
            aggregateType = AGGREGATE_TYPE_FRIEND_REQUEST,
            aggregateId = request.id.value,
            payloadJson =
                json.encodeToString(
                    FriendRequestPayload(
                        friendRequestId = formatGrpcUuid(request.id.value),
                        senderUserId = formatGrpcUuid(request.senderUserId.value),
                        receiverUserId = formatGrpcUuid(request.receiverUserId.value),
                        createdAt = request.createdAt.toString(),
                    ),
                ),
        )
    }

    override fun appendFriendRequestAccepted(
        request: FriendRequest,
        friendship: Friendship,
    ) {
        val respondedAt = requireNotNull(request.respondedAt) {
            "accepted friend request must have respondedAt"
        }

        insert(
            eventType = FriendshipEventTypes.ACCEPTED,
            aggregateType = AGGREGATE_TYPE_FRIENDSHIP,
            aggregateId = friendship.id.value,
            payloadJson =
                json.encodeToString(
                    FriendshipAcceptedPayload(
                        friendRequestId = formatGrpcUuid(request.id.value),
                        friendshipId = formatGrpcUuid(friendship.id.value),
                        userLowId = formatGrpcUuid(friendship.userLowId.value),
                        userHighId = formatGrpcUuid(friendship.userHighId.value),
                        acceptedAt = respondedAt.toString(),
                    ),
                ),
        )
    }

    override fun appendFriendRequestRejected(request: FriendRequest) {
        val respondedAt = requireNotNull(request.respondedAt) {
            "rejected friend request must have respondedAt"
        }

        insert(
            eventType = FriendshipEventTypes.REJECTED,
            aggregateType = AGGREGATE_TYPE_FRIEND_REQUEST,
            aggregateId = request.id.value,
            payloadJson =
                json.encodeToString(
                    FriendRequestRespondedPayload(
                        friendRequestId = formatGrpcUuid(request.id.value),
                        senderUserId = formatGrpcUuid(request.senderUserId.value),
                        receiverUserId = formatGrpcUuid(request.receiverUserId.value),
                        respondedAt = respondedAt.toString(),
                    ),
                ),
        )
    }

    override fun appendFriendRequestCanceled(request: FriendRequest) {
        insert(
            eventType = FriendshipEventTypes.CANCELED,
            aggregateType = AGGREGATE_TYPE_FRIEND_REQUEST,
            aggregateId = request.id.value,
            payloadJson =
                json.encodeToString(
                    FriendRequestCanceledPayload(
                        friendRequestId = formatGrpcUuid(request.id.value),
                        senderUserId = formatGrpcUuid(request.senderUserId.value),
                        receiverUserId = formatGrpcUuid(request.receiverUserId.value),
                        canceledAt = request.canceledAt.toString(),
                    ),
                ),
        )
    }

    override fun appendFriendshipRemoved(friendship: Friendship) {
        insert(
            eventType = FriendshipEventTypes.REMOVED,
            aggregateType = AGGREGATE_TYPE_FRIENDSHIP,
            aggregateId = friendship.id.value,
            payloadJson =
                json.encodeToString(
                    FriendshipRemovedPayload(
                        friendshipId = formatGrpcUuid(friendship.id.value),
                        userLowId = formatGrpcUuid(friendship.userLowId.value),
                        userHighId = formatGrpcUuid(friendship.userHighId.value),
                        removedAt = friendship.removedAt.toString(),
                    ),
                ),
        )
    }

    override fun appendUserBlocked(block: BlockRelation) {
        insert(
            eventType = FriendshipEventTypes.BLOCKED,
            aggregateType = AGGREGATE_TYPE_BLOCK,
            aggregateId = block.blockerUserId.value,
            payloadJson =
                json.encodeToString(
                    UserBlockedPayload(
                        blockerUserId = formatGrpcUuid(block.blockerUserId.value),
                        blockedUserId = formatGrpcUuid(block.blockedUserId.value),
                        createdAt = block.createdAt.toString(),
                    ),
                ),
        )
    }

    override fun appendUserUnblocked(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ) {
        insert(
            eventType = FriendshipEventTypes.UNBLOCKED,
            aggregateType = AGGREGATE_TYPE_BLOCK,
            aggregateId = blockerUserId.value,
            payloadJson =
                json.encodeToString(
                    UserUnblockedPayload(
                        blockerUserId = formatGrpcUuid(blockerUserId.value),
                        blockedUserId = formatGrpcUuid(blockedUserId.value),
                    ),
                ),
        )
    }

    private fun insert(
        eventType: String,
        aggregateType: String,
        aggregateId: Uuid,
        payloadJson: String,
    ) {
        FriendshipOutboxTable.insertEntry(
            OutboxEntry(
                id = Uuid.random(),
                eventType = eventType,
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                payloadJson = payloadJson,
                createdAt = clock.now(),
            ),
        )
    }

    private companion object {
        const val AGGREGATE_TYPE_FRIEND_REQUEST = "friend_request"
        const val AGGREGATE_TYPE_FRIENDSHIP = "friendship"
        const val AGGREGATE_TYPE_BLOCK = "block"

        val json = Json { encodeDefaults = false }
    }
}

object FriendshipOutboxTable : OutboxTable("friendship_outbox")

@Serializable
private data class FriendRequestPayload(
    @SerialName("friend_request_id") val friendRequestId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("receiver_user_id") val receiverUserId: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class FriendshipAcceptedPayload(
    @SerialName("friend_request_id") val friendRequestId: String,
    @SerialName("friendship_id") val friendshipId: String,
    @SerialName("user_low_id") val userLowId: String,
    @SerialName("user_high_id") val userHighId: String,
    @SerialName("accepted_at") val acceptedAt: String,
)

@Serializable
private data class FriendRequestRespondedPayload(
    @SerialName("friend_request_id") val friendRequestId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("receiver_user_id") val receiverUserId: String,
    @SerialName("responded_at") val respondedAt: String,
)

@Serializable
private data class FriendRequestCanceledPayload(
    @SerialName("friend_request_id") val friendRequestId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("receiver_user_id") val receiverUserId: String,
    @SerialName("canceled_at") val canceledAt: String,
)

@Serializable
private data class FriendshipRemovedPayload(
    @SerialName("friendship_id") val friendshipId: String,
    @SerialName("user_low_id") val userLowId: String,
    @SerialName("user_high_id") val userHighId: String,
    @SerialName("removed_at") val removedAt: String,
)

@Serializable
private data class UserBlockedPayload(
    @SerialName("blocker_user_id") val blockerUserId: String,
    @SerialName("blocked_user_id") val blockedUserId: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class UserUnblockedPayload(
    @SerialName("blocker_user_id") val blockerUserId: String,
    @SerialName("blocked_user_id") val blockedUserId: String,
)
