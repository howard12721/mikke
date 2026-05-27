package jp.xhw.mikke.api.auth.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.auth.application.IdentitySessionGateway
import jp.xhw.mikke.api.infrastructure.closeChannel
import jp.xhw.mikke.api.infrastructure.gatewayChannelFromEnvironment
import jp.xhw.mikke.api.infrastructure.grpcGatewayCall
import jp.xhw.mikke.identity.v1.IdentityServiceGrpcKt
import jp.xhw.mikke.identity.v1.TouchSessionRequest
import jp.xhw.mikke.platform.grpc.InternalCallerClientInterceptor

class GrpcIdentitySessionGateway(
    private val channel: ManagedChannel,
    private val stub: IdentityServiceGrpcKt.IdentityServiceCoroutineStub =
        IdentityServiceGrpcKt
            .IdentityServiceCoroutineStub(channel)
            .withInterceptors(InternalCallerClientInterceptor(serviceName = "api")),
) : IdentitySessionGateway {
    override suspend fun touchSession(sessionHash: String) {
        grpcGatewayCall {
            stub.touchSession(
                TouchSessionRequest
                    .newBuilder()
                    .setSessionHash(sessionHash)
                    .build(),
            )
        }
    }

    override fun close() = closeChannel(channel)

    companion object {
        fun fromEnvironment(): GrpcIdentitySessionGateway =
            GrpcIdentitySessionGateway(
                channel =
                    gatewayChannelFromEnvironment(
                        targetEnv = "IDENTITY_SERVICE_TARGET",
                        hostEnv = "IDENTITY_SERVICE_HOST",
                        portEnv = "IDENTITY_SERVICE_PORT",
                        defaultPort = 50051,
                    ),
            )
    }
}
