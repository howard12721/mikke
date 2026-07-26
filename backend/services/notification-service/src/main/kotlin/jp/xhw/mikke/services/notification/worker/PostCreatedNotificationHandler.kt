package jp.xhw.mikke.services.notification.worker

import jp.xhw.mikke.events.core.EventEnvelope
import jp.xhw.mikke.events.post.PostCreatedPayload
import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.platform.events.EventHandler
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.services.notification.application.PostNotificationEnqueuer
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun interface FriendshipReader {
    suspend fun listFriendUserIds(userId: Uuid): List<Uuid>
}

class PostCreatedNotificationHandler(
    private val friendshipReader: FriendshipReader,
    private val notificationEnqueuer: PostNotificationEnqueuer,
) : EventHandler<PostCreatedPayload> {
    override suspend fun handle(event: EventEnvelope<PostCreatedPayload>) {
        if (event.eventType != PostEventTypes.CREATED) return
        if (event.payload.visibility != "FRIENDS" || event.payload.status != "ACTIVE") return

        val eventId = parseGrpcUuid(event.eventId, "event_id")
        val postId = parseGrpcUuid(event.payload.postId, "post_id")
        val authorUserId = parseGrpcUuid(event.payload.authorUserId, "author_user_id")
        val occurredAt =
            try {
                Instant.parse(event.occurredAt)
            } catch (exception: IllegalArgumentException) {
                throw ValidationException("occurred_at must be a valid instant", exception)
            }
        val friendUserIds = friendshipReader.listFriendUserIds(authorUserId)

        notificationEnqueuer.enqueueOnce(
            eventId = eventId,
            postId = postId,
            authorUserId = authorUserId,
            recipientUserIds = friendUserIds,
            occurredAt = occurredAt,
        )
    }
}
