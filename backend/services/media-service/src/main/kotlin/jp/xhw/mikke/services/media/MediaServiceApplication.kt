package jp.xhw.mikke.services.media

import jp.xhw.mikke.platform.auth.grpc.GrpcAuthServerInterceptor
import jp.xhw.mikke.platform.auth.grpc.bearerToken
import jp.xhw.mikke.platform.auth.jwt.JwtTokenService
import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.grpc.GrpcServerExceptionHandling
import jp.xhw.mikke.platform.grpc.InternalRpcServerInterceptor
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait
import jp.xhw.mikke.platform.outbox.OutboxRelay
import jp.xhw.mikke.platform.outbox.RedisOutboxPublisher
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.services.media.application.MediaApplicationException
import jp.xhw.mikke.services.media.application.MediaService
import jp.xhw.mikke.services.media.infrastructure.ExposedMediaRepository
import jp.xhw.mikke.services.media.infrastructure.ObjectStorageConfig
import jp.xhw.mikke.services.media.infrastructure.S3ObjectStorageClient
import jp.xhw.mikke.services.media.infrastructure.outbox.ExposedMediaOutbox
import jp.xhw.mikke.services.media.infrastructure.outbox.MediaOutboxTable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    val database = connectMariaDbFromEnv(defaultDatabase = "media_service")
    val redis = connectRedisFromEnv()
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
    val outboxRelay =
        OutboxRelay(
            publisher =
                RedisOutboxPublisher(
                    outboxTable = MediaOutboxTable,
                    transactionRunner = transactionRunner,
                    producer =
                        RedisStreamProducer(
                            commands = redis.connection.sync(),
                            streamName = System.getenv("MEDIA_EVENTS_STREAM") ?: "mikke.media.events",
                        ),
                    producerName = "media-service",
                    publisherId =
                        System.getenv("OUTBOX_PUBLISHER_ID") ?: "media-service-${ProcessHandle.current().pid()}",
                    batchSize = System.getenv("OUTBOX_RELAY_BATCH_SIZE")?.toIntOrNull() ?: 100,
                    leaseDuration = System.getenv("OUTBOX_RELAY_LEASE_SECONDS")?.toLongOrNull()?.seconds ?: 30.seconds,
                ),
            idleDelay =
                System.getenv("OUTBOX_RELAY_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: 500.milliseconds,
            errorDelay = System.getenv("OUTBOX_RELAY_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds ?: 5.seconds,
        )
    outboxRelay.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            outboxRelay.stop()
            redis.close()
        },
    )

    grpcServer(
        serviceName = "media-service",
        portEnv = "MEDIA_SERVICE_PORT",
        defaultPort = 50054,
        exceptionHandling =
            GrpcServerExceptionHandling(
                internalErrorDescription = "Internal media service error",
                domainExceptionMapper =
                    { throwable ->
                        (throwable as? MediaApplicationException)?.toGrpcStatus()
                    },
            ),
    ) {
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
