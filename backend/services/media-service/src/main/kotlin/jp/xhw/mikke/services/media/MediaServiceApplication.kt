package jp.xhw.mikke.services.media

import jp.xhw.mikke.platform.auth.grpc.GrpcAuthServerInterceptor
import jp.xhw.mikke.platform.auth.grpc.bearerToken
import jp.xhw.mikke.platform.auth.jwt.JwtTokenService
import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.grpc.InternalRpcServerInterceptor
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait
import jp.xhw.mikke.services.media.application.ExposedMediaOutbox
import jp.xhw.mikke.services.media.application.MediaService
import jp.xhw.mikke.services.media.infrastructure.ExposedMediaRepository
import jp.xhw.mikke.services.media.infrastructure.ObjectStorageConfig
import jp.xhw.mikke.services.media.infrastructure.S3ObjectStorageClient

fun main() {
    val database = connectMariaDbFromEnv(defaultDatabase = "media_service")
    val mediaRepository = ExposedMediaRepository()
    val mediaOutbox = ExposedMediaOutbox()
    val transactionRunner = ExposedTransactionRunner(database)
    val objectStorageClient = S3ObjectStorageClient(ObjectStorageConfig.fromEnv())
    val deliveryUrlBuilder = MediaServiceConfig.deliveryUrlBuilder()
    val mediaApplicationService =
        MediaService(
            mediaRepository = mediaRepository,
            mediaOutbox = mediaOutbox,
            objectStorageClient = objectStorageClient,
            deliveryUrlBuilder = deliveryUrlBuilder,
            transactionRunner = transactionRunner,
        )
    val mediaServiceRpc = MediaServiceRpc(mediaService = mediaApplicationService)
    val tokenService = JwtTokenService(secret = System.getenv("IDENTITY_JWT_SECRET") ?: "dev-identity-secret")

    grpcServer(serviceName = "media-service", portEnv = "MEDIA_SERVICE_PORT", defaultPort = 50054) {
        installGrpcHealth(serviceName = "media-service")
        intercept(InternalRpcServerInterceptor())
        intercept(
            GrpcAuthServerInterceptor(
                authenticator = { headers ->
                    headers.bearerToken()?.let(tokenService::authenticateAccessToken)
                },
                optional = true,
            ),
        )
        addService(mediaServiceRpc)
    }.startAndAwait()
}
