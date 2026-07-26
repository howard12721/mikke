package jp.xhw.mikke.services.notification.application

import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.events.ProcessedEventMarkResult
import jp.xhw.mikke.platform.events.ProcessedEventStore
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.services.notification.infrastructure.NotificationDeliveriesTable
import jp.xhw.mikke.services.notification.infrastructure.NotificationPreferencesTable
import jp.xhw.mikke.services.notification.infrastructure.NotificationProcessedEventsTable
import jp.xhw.mikke.services.notification.infrastructure.NotificationsTable
import jp.xhw.mikke.services.notification.infrastructure.PushRegistrationsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val POST_NOTIFICATION_TYPE = "POST_CREATED"
private const val POST_NOTIFICATION_TITLE = "Mikke"
private const val POST_NOTIFICATION_BODY = "フレンドが新しい写真を投稿しました"

interface PostNotificationEnqueuer {
    fun enqueueOnce(
        eventId: Uuid,
        postId: Uuid,
        authorUserId: Uuid,
        recipientUserIds: List<Uuid>,
        occurredAt: Instant,
    ): Boolean
}

class ExposedPostNotificationEnqueuer(
    private val transactionRunner: TransactionRunner,
    private val processedEventStore: ProcessedEventStore =
        ProcessedEventStore(NotificationProcessedEventsTable),
) : PostNotificationEnqueuer {
    override fun enqueueOnce(
        eventId: Uuid,
        postId: Uuid,
        authorUserId: Uuid,
        recipientUserIds: List<Uuid>,
        occurredAt: Instant,
    ): Boolean =
        transactionRunner.runInTransaction {
            when (processedEventStore.tryMarkProcessed(eventId, PostEventTypes.CREATED)) {
                ProcessedEventMarkResult.AlreadyProcessed -> false
                ProcessedEventMarkResult.Recorded -> {
                    enqueueNotifications(
                        eventId = eventId,
                        postId = postId,
                        authorUserId = authorUserId,
                        recipientUserIds = recipientUserIds.distinct(),
                        occurredAt = occurredAt,
                    )
                    true
                }
            }
        }

    private fun enqueueNotifications(
        eventId: Uuid,
        postId: Uuid,
        authorUserId: Uuid,
        recipientUserIds: List<Uuid>,
        occurredAt: Instant,
    ) {
        if (recipientUserIds.isEmpty()) return

        val disabledRecipientIds =
            NotificationPreferencesTable
                .selectAll()
                .where {
                    (NotificationPreferencesTable.userId inList recipientUserIds) and
                        (NotificationPreferencesTable.postCreatedEnabled eq false)
                }.mapTo(mutableSetOf()) { row -> row[NotificationPreferencesTable.userId] }
        val enabledRecipientIds = recipientUserIds.filterNot(disabledRecipientIds::contains)
        if (enabledRecipientIds.isEmpty()) return

        val registrationsByUser =
            PushRegistrationsTable
                .selectAll()
                .where {
                    (PushRegistrationsTable.userId inList enabledRecipientIds) and
                        (PushRegistrationsTable.enabled eq true)
                }.groupBy { row -> row[PushRegistrationsTable.userId] }

        enabledRecipientIds.forEach { recipientUserId ->
            val notificationId = Uuid.random()
            NotificationsTable.insert { row ->
                row[id] = notificationId
                row[NotificationsTable.recipientUserId] = recipientUserId
                row[type] = POST_NOTIFICATION_TYPE
                row[title] = POST_NOTIFICATION_TITLE
                row[body] = POST_NOTIFICATION_BODY
                row[relatedPostId] = postId
                row[relatedUserId] = authorUserId
                row[dedupeKey] = "${PostEventTypes.CREATED}:$eventId:$recipientUserId"
                row[createdAt] = occurredAt.toJavaInstant()
                row[readAt] = null
            }

            registrationsByUser[recipientUserId].orEmpty().forEach { registration ->
                NotificationDeliveriesTable.insert { row ->
                    row[id] = Uuid.random()
                    row[NotificationDeliveriesTable.notificationId] = notificationId
                    row[pushRegistrationId] = registration[PushRegistrationsTable.id]
                    row[status] = "PENDING"
                    row[attemptCount] = 0
                    row[nextAttemptAt] = occurredAt.toJavaInstant()
                    row[leaseOwner] = null
                    row[leaseExpiresAt] = null
                    row[providerMessageId] = null
                    row[lastError] = null
                    row[createdAt] = occurredAt.toJavaInstant()
                    row[sentAt] = null
                    row[failedAt] = null
                }
            }
        }
    }
}
