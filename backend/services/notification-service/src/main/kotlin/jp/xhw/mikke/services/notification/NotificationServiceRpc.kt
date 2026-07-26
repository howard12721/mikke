package jp.xhw.mikke.services.notification

import jp.xhw.mikke.notification.v1.DeletePushTokenRequest
import jp.xhw.mikke.notification.v1.DeletePushTokenResponse
import jp.xhw.mikke.notification.v1.NotificationServiceGrpcKt
import jp.xhw.mikke.notification.v1.PushPlatform
import jp.xhw.mikke.notification.v1.PushToken
import jp.xhw.mikke.notification.v1.RegisterPushTokenRequest
import jp.xhw.mikke.notification.v1.RegisterPushTokenResponse
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.grpc.requireUserUuid
import jp.xhw.mikke.platform.grpc.withGrpcExceptionMapping
import jp.xhw.mikke.platform.time.toProtoTimestamp
import jp.xhw.mikke.services.notification.application.DeletePushInstallationCommand
import jp.xhw.mikke.services.notification.application.PushRegistrationService
import jp.xhw.mikke.services.notification.application.RegisterPushInstallationCommand
import jp.xhw.mikke.services.notification.model.PushRegistration
import java.util.logging.Logger
import jp.xhw.mikke.services.notification.model.PushPlatform as DomainPushPlatform

private val logger = Logger.getLogger("notification-service")

class NotificationServiceRpc(
    private val pushRegistrationService: PushRegistrationService,
) : NotificationServiceGrpcKt.NotificationServiceCoroutineImplBase() {
    override suspend fun registerPushToken(request: RegisterPushTokenRequest): RegisterPushTokenResponse =
        mapRpcExceptions {
            val registration =
                pushRegistrationService.register(
                    RegisterPushInstallationCommand(
                        userId = request.actor.requireUserUuid(),
                        deviceId = request.deviceId,
                        platform = request.platform.toDomain(),
                        firebaseInstallationId = request.firebaseInstallationId,
                    ),
                )
            RegisterPushTokenResponse
                .newBuilder()
                .setPushToken(registration.toProto())
                .build()
        }

    override suspend fun deletePushToken(request: DeletePushTokenRequest): DeletePushTokenResponse =
        mapRpcExceptions {
            pushRegistrationService.delete(
                DeletePushInstallationCommand(
                    userId = request.actor.requireUserUuid(),
                    deviceId = request.deviceId,
                    platform = request.platform.toDomain(),
                ),
            )
            DeletePushTokenResponse.getDefaultInstance()
        }
}

private fun PushPlatform.toDomain(): DomainPushPlatform =
    when (this) {
        PushPlatform.PUSH_PLATFORM_IOS -> DomainPushPlatform.IOS
        PushPlatform.PUSH_PLATFORM_ANDROID -> DomainPushPlatform.ANDROID
        PushPlatform.PUSH_PLATFORM_UNSPECIFIED,
        PushPlatform.UNRECOGNIZED,
        -> throw ValidationException("platform must be IOS or ANDROID")
    }

private fun PushRegistration.toProto(): PushToken =
    PushToken
        .newBuilder()
        .setId(id.toString())
        .setUserId(userId.toString())
        .setDeviceId(deviceId)
        .setPlatform(
            when (platform) {
                DomainPushPlatform.IOS -> PushPlatform.PUSH_PLATFORM_IOS
                DomainPushPlatform.ANDROID -> PushPlatform.PUSH_PLATFORM_ANDROID
            },
        ).setEnabled(enabled)
        .setCreatedAt(createdAt.toProtoTimestamp())
        .setLastSeenAt(lastSeenAt.toProtoTimestamp())
        .build()

private suspend inline fun <T> mapRpcExceptions(crossinline block: suspend () -> T): T =
    withGrpcExceptionMapping(
        logger = logger,
        serviceName = "notification-service",
        internalErrorDescription = "Internal notification service error",
    ) {
        block()
    }
