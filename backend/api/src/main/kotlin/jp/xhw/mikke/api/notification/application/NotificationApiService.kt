package jp.xhw.mikke.api.notification.application

import jp.xhw.mikke.api.common.application.requireText
import jp.xhw.mikke.api.graphql.ApiRequestContext

class NotificationApiService(
    private val notificationGateway: NotificationGateway,
) {
    suspend fun registerPushInstallation(
        context: ApiRequestContext,
        deviceId: String,
        platform: PushPlatform,
        firebaseInstallationId: String,
    ): PushInstallation =
        notificationGateway.registerPushInstallation(
            context = context,
            deviceId = deviceId.requireText("deviceId"),
            platform = platform,
            firebaseInstallationId = firebaseInstallationId.requireText("firebaseInstallationId"),
        )

    suspend fun deletePushInstallation(
        context: ApiRequestContext,
        deviceId: String,
        platform: PushPlatform,
    ) {
        notificationGateway.deletePushInstallation(
            context = context,
            deviceId = deviceId.requireText("deviceId"),
            platform = platform,
        )
    }
}
