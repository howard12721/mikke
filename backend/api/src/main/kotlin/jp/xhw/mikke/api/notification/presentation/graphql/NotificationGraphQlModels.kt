package jp.xhw.mikke.api.notification.presentation.graphql

import jp.xhw.mikke.api.notification.application.PushPlatform

data class RegisterPushInstallationInput(
    val deviceId: String,
    val platform: PushPlatform,
    val firebaseInstallationId: String,
)

data class DeletePushInstallationInput(
    val deviceId: String,
    val platform: PushPlatform,
)

data class PushInstallation(
    val id: String,
    val deviceId: String,
    val platform: PushPlatform,
    val enabled: Boolean,
    val lastSeenAt: String,
)

fun jp.xhw.mikke.api.notification.application.PushInstallation.toGraphQl(): PushInstallation =
    PushInstallation(
        id = id,
        deviceId = deviceId,
        platform = platform,
        enabled = enabled,
        lastSeenAt = lastSeenAt,
    )
