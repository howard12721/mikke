package jp.xhw.mikke.services.notification.worker

import jp.xhw.mikke.events.core.EventEnvelope
import jp.xhw.mikke.events.post.PostCreatedPayload
import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.services.notification.application.PostNotificationEnqueuer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PostCreatedNotificationHandlerTest {
    @Test
    fun `enqueues the post notification for every friend`() =
        runBlocking {
            val authorUserId = Uuid.random()
            val postId = Uuid.random()
            val eventId = Uuid.random()
            val friendUserIds = listOf(Uuid.random(), Uuid.random())
            val enqueuer = RecordingPostNotificationEnqueuer()
            val handler =
                PostCreatedNotificationHandler(
                    friendshipReader =
                        FriendshipReader { userId ->
                            assertEquals(authorUserId, userId)
                            friendUserIds
                        },
                    notificationEnqueuer = enqueuer,
                )

            handler.handle(
                EventEnvelope(
                    eventId = eventId.toString(),
                    eventType = PostEventTypes.CREATED,
                    eventVersion = 1,
                    occurredAt = "2026-07-26T08:00:00Z",
                    producer = "post-service",
                    aggregateType = "post",
                    aggregateId = postId.toString(),
                    payload =
                        PostCreatedPayload(
                            postId = postId.toString(),
                            authorUserId = authorUserId.toString(),
                            mediaId = Uuid.random().toString(),
                            visibility = "FRIENDS",
                            status = "ACTIVE",
                            createdAt = "2026-07-26T08:00:00Z",
                        ),
                ),
            )

            assertEquals(
                RecordedPostNotification(
                    eventId = eventId,
                    postId = postId,
                    authorUserId = authorUserId,
                    recipientUserIds = friendUserIds,
                    occurredAt = Instant.parse("2026-07-26T08:00:00Z"),
                ),
                enqueuer.recorded.single(),
            )
        }

    @Test
    fun `ignores a post that is not visible to friends`() =
        runBlocking {
            val enqueuer = RecordingPostNotificationEnqueuer()
            val handler =
                PostCreatedNotificationHandler(
                    friendshipReader =
                        FriendshipReader {
                            error("Friendship lookup must not run for a private post")
                        },
                    notificationEnqueuer = enqueuer,
                )

            handler.handle(
                EventEnvelope(
                    eventId = Uuid.random().toString(),
                    eventType = PostEventTypes.CREATED,
                    eventVersion = 1,
                    occurredAt = "2026-07-26T08:00:00Z",
                    producer = "post-service",
                    aggregateType = "post",
                    aggregateId = Uuid.random().toString(),
                    payload =
                        PostCreatedPayload(
                            postId = Uuid.random().toString(),
                            authorUserId = Uuid.random().toString(),
                            mediaId = Uuid.random().toString(),
                            visibility = "PRIVATE",
                            status = "ACTIVE",
                            createdAt = "2026-07-26T08:00:00Z",
                        ),
                ),
            )

            assertTrue(enqueuer.recorded.isEmpty())
        }
}

private class RecordingPostNotificationEnqueuer : PostNotificationEnqueuer {
    val recorded = mutableListOf<RecordedPostNotification>()

    override fun enqueueOnce(
        eventId: Uuid,
        postId: Uuid,
        authorUserId: Uuid,
        recipientUserIds: List<Uuid>,
        occurredAt: Instant,
    ): Boolean {
        recorded +=
            RecordedPostNotification(
                eventId = eventId,
                postId = postId,
                authorUserId = authorUserId,
                recipientUserIds = recipientUserIds,
                occurredAt = occurredAt,
            )
        return true
    }
}

private data class RecordedPostNotification(
    val eventId: Uuid,
    val postId: Uuid,
    val authorUserId: Uuid,
    val recipientUserIds: List<Uuid>,
    val occurredAt: Instant,
)
