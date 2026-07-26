package jp.xhw.mikke.services.notification.infrastructure

import jp.xhw.mikke.platform.events.exposed.ProcessedEventsTable
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.platform.uuid.exposed.uuidBinaryNullable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

object NotificationsTable : Table("notifications") {
    val id = uuidBinary("id")
    val recipientUserId = uuidBinary("recipient_user_id")
    val type = varchar("type", 64)
    val title = varchar("title", 255)
    val body = varchar("body", 1024)
    val relatedPostId = uuidBinaryNullable("related_post_id")
    val relatedUserId = uuidBinaryNullable("related_user_id")
    val dedupeKey = varchar("dedupe_key", 255)
    val createdAt = timestamp("created_at")
    val readAt = timestamp("read_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_notifications_dedupe_key", dedupeKey)
    }
}

object NotificationPreferencesTable : Table("notification_preferences") {
    val userId = uuidBinary("user_id")
    val postCreatedEnabled = bool("post_created_enabled")
    val friendRequestEnabled = bool("friend_request_enabled")
    val friendAcceptedEnabled = bool("friend_accepted_enabled")
    val guessSubmittedEnabled = bool("guess_submitted_enabled")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object NotificationDeliveriesTable : Table("notification_deliveries") {
    val id = uuidBinary("id")
    val notificationId = uuidBinary("notification_id")
    val pushRegistrationId = uuidBinary("push_token_id")
    val status = varchar("status", 32)
    val attemptCount = integer("attempt_count")
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseOwner = varchar("lease_owner", 128).nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val providerMessageId = varchar("provider_message_id", 255).nullable()
    val lastError = varchar("last_error", 1024).nullable()
    val createdAt = timestamp("created_at")
    val sentAt = timestamp("sent_at").nullable()
    val failedAt = timestamp("failed_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(
            "uq_notification_deliveries_notification_push_token",
            notificationId,
            pushRegistrationId,
        )
    }
}

object NotificationProcessedEventsTable : ProcessedEventsTable("notification_processed_events")
