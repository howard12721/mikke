package jp.xhw.mikke.services.media

import jp.xhw.mikke.events.media.MediaEventTypes
import jp.xhw.mikke.events.media.MediaUploadCompletedPayload
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthServerInterceptor
import jp.xhw.mikke.platform.auth.grpc.bearerToken
import jp.xhw.mikke.platform.auth.jwt.JwtTokenService
import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.events.ProcessedEventStore
import jp.xhw.mikke.platform.events.subscription.EventHandlerRegistration
import jp.xhw.mikke.platform.events.subscription.RedisDeadLetterSink
import jp.xhw.mikke.platform.events.subscription.RedisEventSubscription
import jp.xhw.mikke.platform.grpc.GrpcServerExceptionHandling
import jp.xhw.mikke.platform.grpc.InternalRpcServerInterceptor
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait
import jp.xhw.mikke.platform.outbox.OutboxRelay
import jp.xhw.mikke.platform.outbox.RedisOutboxPublisher
import jp.xhw.mikke.platform.redis.RedisStreamConsumerGroup
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.services.media.application.MediaApplicationException
import jp.xhw.mikke.services.media.application.MediaDeliveryUrlBuilder
import jp.xhw.mikke.services.media.application.MediaService
import jp.xhw.mikke.services.media.infrastructure.ExposedMediaRepository
import jp.xhw.mikke.services.media.infrastructure.ObjectStorageConfig
import jp.xhw.mikke.services.media.infrastructure.S3ObjectStorageClient
import jp.xhw.mikke.services.media.infrastructure.outbox.ExposedMediaOutbox
import jp.xhw.mikke.services.media.infrastructure.outbox.MediaOutboxTable
import jp.xhw.mikke.services.media.worker.ImageIoThumbnailGenerator
import jp.xhw.mikke.services.media.worker.MediaProcessedEventsTable
import jp.xhw.mikke.services.media.worker.MediaUploadCompletedHandler
import java.net.InetAddress
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    ImageIO.setUseCache(false)

    val database = connectMariaDbFromEnv(defaultDatabase = "media_service")
    val redis = connectRedisFromEnv()
    val mediaRepository = ExposedMediaRepository()
    val mediaOutbox = ExposedMediaOutbox()
    val transactionRunner = ExposedTransactionRunner(database)
    val objectStorageClient = S3ObjectStorageClient(ObjectStorageConfig.fromEnv())
    val deliveryUrlBuilder =
        MediaDeliveryUrlBuilder(
            objectStorageClient = objectStorageClient,
            expiresIn = MediaServiceConfig.deliveryUrlTtl(),
        )
    val maxThumbnailSourcePixels = System.getenv("MEDIA_THUMBNAIL_MAX_SOURCE_PIXELS")?.toLongOrNull() ?: 24_000_000L
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
    val mediaEventsStream = System.getenv("MEDIA_EVENTS_STREAM") ?: "mikke.media.events"
    val instanceId = mediaServiceInstanceId()
    val outboxRelay =
        OutboxRelay(
            publisher =
                RedisOutboxPublisher(
                    outboxTable = MediaOutboxTable,
                    transactionRunner = transactionRunner,
                    producer =
                        RedisStreamProducer(
                            commands = redis.connection.sync(),
                            streamName = mediaEventsStream,
                        ),
                    producerName = "media-service",
                    publisherId =
                        System.getenv("OUTBOX_PUBLISHER_ID") ?: "media-service-$instanceId",
                    batchSize = System.getenv("OUTBOX_RELAY_BATCH_SIZE")?.toIntOrNull() ?: 100,
                    leaseDuration = System.getenv("OUTBOX_RELAY_LEASE_SECONDS")?.toLongOrNull()?.seconds ?: 30.seconds,
                ),
            idleDelay =
                System.getenv("OUTBOX_RELAY_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: 500.milliseconds,
            errorDelay = System.getenv("OUTBOX_RELAY_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds ?: 5.seconds,
        )
    outboxRelay.start()

    val mediaDeadLetterStream = System.getenv("MEDIA_EVENTS_DEAD_LETTER_STREAM") ?: "$mediaEventsStream.dead"
    val thumbnailSubscription =
        RedisEventSubscription(
            consumerGroup =
                RedisStreamConsumerGroup(
                    commands = redis.connection.sync(),
                    streamName = mediaEventsStream,
                    consumerGroup = System.getenv("MEDIA_THUMBNAIL_CONSUMER_GROUP") ?: "media-thumbnail-worker",
                    consumerName =
                        System.getenv("MEDIA_THUMBNAIL_CONSUMER_NAME")
                            ?: "media-service-$instanceId",
                ),
            handlers =
                listOf(
                    EventHandlerRegistration(
                        eventType = MediaEventTypes.UPLOAD_COMPLETED,
                        eventVersion = 1,
                        payloadSerializer = MediaUploadCompletedPayload.serializer(),
                        handler =
                            MediaUploadCompletedHandler(
                                mediaRepository = mediaRepository,
                                mediaOutbox = mediaOutbox,
                                objectStorageClient = objectStorageClient,
                                transactionRunner = transactionRunner,
                                processedEventStore = ProcessedEventStore(MediaProcessedEventsTable),
                                thumbnailGenerator = ImageIoThumbnailGenerator(maxSourcePixels = maxThumbnailSourcePixels),
                                maxSizePx = System.getenv("MEDIA_THUMBNAIL_MAX_SIZE_PX")?.toIntOrNull() ?: 512,
                                maxOriginalBytes =
                                    System.getenv("MEDIA_THUMBNAIL_MAX_ORIGINAL_BYTES")?.toLongOrNull()
                                        ?: (20L * 1024L * 1024L),
                            ),
                    ),
                ),
            deadLetterSink =
                RedisDeadLetterSink(
                    RedisStreamProducer(
                        commands = redis.connection.sync(),
                        streamName = mediaDeadLetterStream,
                    ),
                ),
            startId = System.getenv("MEDIA_THUMBNAIL_CONSUMER_GROUP_START_ID") ?: "$",
            maxDeliveryAttempts = System.getenv("MEDIA_THUMBNAIL_MAX_DELIVERY_ATTEMPTS")?.toIntOrNull() ?: 10,
            readCount = System.getenv("MEDIA_THUMBNAIL_READ_COUNT")?.toLongOrNull() ?: 10,
            staleMinIdle =
                Duration.ofSeconds(
                    System.getenv("MEDIA_THUMBNAIL_STALE_MIN_IDLE_SECONDS")?.toLongOrNull() ?: 300,
                ),
            idleDelay =
                System.getenv("MEDIA_THUMBNAIL_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: 500.milliseconds,
            errorDelay =
                System.getenv("MEDIA_THUMBNAIL_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: 5.seconds,
        )
    thumbnailSubscription.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            thumbnailSubscription.stop()
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

private fun mediaServiceInstanceId(): String =
    listOfNotNull(
        System.getenv("HOSTNAME"),
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
    ).firstOrNull { it.isNotBlank() }
        ?: "unknown-host-${ProcessHandle.current().pid()}"
