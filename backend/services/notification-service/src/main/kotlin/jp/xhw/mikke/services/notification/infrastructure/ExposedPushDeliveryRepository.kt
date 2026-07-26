package jp.xhw.mikke.services.notification.infrastructure

import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.services.notification.application.PushDeliveryRepository
import jp.xhw.mikke.services.notification.model.PushDelivery
import jp.xhw.mikke.services.notification.model.PushMessage
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedPushDeliveryRepository : PushDeliveryRepository {
    override fun claimReady(
        workerId: String,
        limit: Int,
        now: Instant,
        leaseUntil: Instant,
    ): List<PushDelivery> {
        if (limit <= 0) return emptyList()

        val nowJava = now.toJavaInstant()
        val candidates =
            NotificationDeliveriesTable
                .selectAll()
                .where {
                    (NotificationDeliveriesTable.status eq "PENDING") and
                        (NotificationDeliveriesTable.nextAttemptAt less nowJava) and
                        (
                            NotificationDeliveriesTable.leaseExpiresAt.isNull() or
                                (NotificationDeliveriesTable.leaseExpiresAt less nowJava)
                        )
                }.orderBy(
                    NotificationDeliveriesTable.nextAttemptAt to SortOrder.ASC,
                    NotificationDeliveriesTable.createdAt to SortOrder.ASC,
                ).limit(limit)
                .map { row ->
                    row[NotificationDeliveriesTable.id] to row[NotificationDeliveriesTable.attemptCount]
                }

        return candidates.mapNotNull { (deliveryId, previousAttemptCount) ->
            val claimed =
                NotificationDeliveriesTable.update({
                    (NotificationDeliveriesTable.id eq deliveryId) and
                        (NotificationDeliveriesTable.status eq "PENDING") and
                        (NotificationDeliveriesTable.nextAttemptAt less nowJava) and
                        (
                            NotificationDeliveriesTable.leaseExpiresAt.isNull() or
                                (NotificationDeliveriesTable.leaseExpiresAt less nowJava)
                        )
                }) { row ->
                    row[leaseOwner] = workerId
                    row[leaseExpiresAt] = leaseUntil.toJavaInstant()
                    row[attemptCount] = previousAttemptCount + 1
                    row[lastError] = null
                } == 1

            if (claimed) loadDelivery(deliveryId) else null
        }
    }

    override fun markSent(
        deliveryId: Uuid,
        workerId: String,
        providerMessageId: String,
        sentAt: Instant,
    ): Boolean =
        NotificationDeliveriesTable.update({
            (NotificationDeliveriesTable.id eq deliveryId) and
                (NotificationDeliveriesTable.leaseOwner eq workerId)
        }) { row ->
            row[status] = "SENT"
            row[NotificationDeliveriesTable.providerMessageId] = providerMessageId.take(255)
            row[NotificationDeliveriesTable.sentAt] = sentAt.toJavaInstant()
            row[leaseOwner] = null
            row[leaseExpiresAt] = null
            row[lastError] = null
        } == 1

    override fun markFailed(
        deliveryId: Uuid,
        workerId: String,
        error: String,
        retryAt: Instant,
        permanent: Boolean,
        failedAt: Instant,
    ): Boolean =
        NotificationDeliveriesTable.update({
            (NotificationDeliveriesTable.id eq deliveryId) and
                (NotificationDeliveriesTable.leaseOwner eq workerId)
        }) { row ->
            row[status] = if (permanent) "FAILED" else "PENDING"
            row[nextAttemptAt] = retryAt.toJavaInstant()
            row[leaseOwner] = null
            row[leaseExpiresAt] = null
            row[lastError] = error.take(1024)
            row[NotificationDeliveriesTable.failedAt] = if (permanent) failedAt.toJavaInstant() else null
        } == 1

    override fun disableRegistration(
        registrationId: Uuid,
        disabledAt: Instant,
    ) {
        PushRegistrationsTable.update({ PushRegistrationsTable.id eq registrationId }) { row ->
            row[enabled] = false
            row[PushRegistrationsTable.disabledAt] = disabledAt.toJavaInstant()
        }
    }

    private fun loadDelivery(deliveryId: Uuid): PushDelivery? {
        val delivery =
            NotificationDeliveriesTable
                .selectAll()
                .where { NotificationDeliveriesTable.id eq deliveryId }
                .singleOrNull()
                ?: return null
        val notification =
            NotificationsTable
                .selectAll()
                .where { NotificationsTable.id eq delivery[NotificationDeliveriesTable.notificationId] }
                .singleOrNull()
                ?: return null
        val registration =
            PushRegistrationsTable
                .selectAll()
                .where { PushRegistrationsTable.id eq delivery[NotificationDeliveriesTable.pushRegistrationId] }
                .singleOrNull()
                ?: return null

        return PushDelivery(
            id = deliveryId,
            registrationId = registration[PushRegistrationsTable.id],
            encryptedInstallationId = registration[PushRegistrationsTable.registrationEncrypted],
            registrationEnabled = registration[PushRegistrationsTable.enabled],
            attemptCount = delivery[NotificationDeliveriesTable.attemptCount],
            message =
                PushMessage(
                    title = notification[NotificationsTable.title],
                    body = notification[NotificationsTable.body],
                    postId = requireNotNull(notification[NotificationsTable.relatedPostId]),
                    authorUserId = requireNotNull(notification[NotificationsTable.relatedUserId]),
                ),
        )
    }
}
