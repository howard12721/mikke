package jp.xhw.mikke.api.notification.application

import jp.xhw.mikke.api.graphql.ApiRequestContext

enum class PushPlatform {
    IOS,
    ANDROID,
}

data class PushInstallation(
    val id: String,
    val deviceId: String,
    val platform: PushPlatform,
    val enabled: Boolean,
    val lastSeenAt: String,
)

interface NotificationGateway : AutoCloseable {
    suspend fun registerPushInstallation(
        context: ApiRequestContext,
        deviceId: String,
        platform: PushPlatform,
        firebaseInstallationId: String,
    ): PushInstallation

    suspend fun deletePushInstallation(
        context: ApiRequestContext,
        deviceId: String,
        platform: PushPlatform,
    )
}
