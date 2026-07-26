package jp.xhw.mikke.api.notification.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.infrastructure.closeChannel
import jp.xhw.mikke.api.infrastructure.gatewayChannelFromEnvironment
import jp.xhw.mikke.api.infrastructure.grpcGatewayCall
import jp.xhw.mikke.api.infrastructure.requireActorProto
import jp.xhw.mikke.api.infrastructure.toIsoString
import jp.xhw.mikke.api.infrastructure.withInternalAuth
import jp.xhw.mikke.api.notification.application.NotificationGateway
import jp.xhw.mikke.api.notification.application.PushInstallation
import jp.xhw.mikke.api.notification.application.PushPlatform
import jp.xhw.mikke.notification.v1.DeletePushTokenRequest
import jp.xhw.mikke.notification.v1.NotificationServiceGrpcKt
import jp.xhw.mikke.notification.v1.RegisterPushTokenRequest
import jp.xhw.mikke.notification.v1.PushPlatform as ProtoPushPlatform

class GrpcNotificationGateway(
    private val channel: ManagedChannel,
    private val stub: NotificationServiceGrpcKt.NotificationServiceCoroutineStub =
        NotificationServiceGrpcKt.NotificationServiceCoroutineStub(channel).withInternalAuth(),
) : NotificationGateway {
    override suspend fun registerPushInstallation(
        context: ApiRequestContext,
        deviceId: String,
        platform: PushPlatform,
        firebaseInstallationId: String,
    ): PushInstallation =
        grpcGatewayCall {
            val token =
                stub
                    .registerPushToken(
                        RegisterPushTokenRequest
                            .newBuilder()
                            .setDeviceId(deviceId)
                            .setPlatform(platform.toProto())
                            .setFirebaseInstallationId(firebaseInstallationId)
                            .setActor(context.requireActorProto())
                            .build(),
                    ).pushToken
            PushInstallation(
                id = token.id,
                deviceId = token.deviceId,
                platform = token.platform.toDomain(),
                enabled = token.enabled,
                lastSeenAt = token.lastSeenAt.toIsoString(),
            )
        }

    override suspend fun deletePushInstallation(
        context: ApiRequestContext,
        deviceId: String,
        platform: PushPlatform,
    ) {
        grpcGatewayCall {
            stub.deletePushToken(
                DeletePushTokenRequest
                    .newBuilder()
                    .setDeviceId(deviceId)
                    .setPlatform(platform.toProto())
                    .setActor(context.requireActorProto())
                    .build(),
            )
        }
    }

    override fun close() = closeChannel(channel)

    companion object {
        fun fromEnvironment(): GrpcNotificationGateway =
            GrpcNotificationGateway(
                gatewayChannelFromEnvironment(
                    targetEnv = "NOTIFICATION_SERVICE_TARGET",
                    hostEnv = "NOTIFICATION_SERVICE_HOST",
                    portEnv = "NOTIFICATION_SERVICE_PORT",
                    defaultPort = 50057,
                ),
            )
    }
}

private fun PushPlatform.toProto(): ProtoPushPlatform =
    when (this) {
        PushPlatform.IOS -> ProtoPushPlatform.PUSH_PLATFORM_IOS
        PushPlatform.ANDROID -> ProtoPushPlatform.PUSH_PLATFORM_ANDROID
    }

private fun ProtoPushPlatform.toDomain(): PushPlatform =
    when (this) {
        ProtoPushPlatform.PUSH_PLATFORM_IOS -> PushPlatform.IOS
        ProtoPushPlatform.PUSH_PLATFORM_ANDROID -> PushPlatform.ANDROID
        ProtoPushPlatform.PUSH_PLATFORM_UNSPECIFIED,
        ProtoPushPlatform.UNRECOGNIZED,
        -> error("Notification service returned an unspecified push platform")
    }
