package jp.xhw.mikke.services.notification.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

enum class PushPlatform {
    IOS,
    ANDROID,
}

data class PushRegistration(
    val id: Uuid,
    val userId: Uuid,
    val deviceId: String,
    val platform: PushPlatform,
    val enabled: Boolean,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)

data class StoredPushRegistration(
    val registration: PushRegistration,
    val registrationHash: String,
    val encryptedInstallationId: String,
)

data class PushMessage(
    val title: String,
    val body: String,
    val postId: Uuid,
    val authorUserId: Uuid,
)

data class PushDelivery(
    val id: Uuid,
    val registrationId: Uuid,
    val encryptedInstallationId: String,
    val registrationEnabled: Boolean,
    val attemptCount: Int,
    val message: PushMessage,
)
