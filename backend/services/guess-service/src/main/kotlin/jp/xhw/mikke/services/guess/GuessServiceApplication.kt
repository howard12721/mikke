package jp.xhw.mikke.services.guess

import io.grpc.ManagedChannel
import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.events.post.PostCreatedPayload
import jp.xhw.mikke.events.post.PostDeletedPayload
import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthServerInterceptor
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
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
import jp.xhw.mikke.platform.grpc.grpcClientChannelFromEnvironment
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait
import jp.xhw.mikke.platform.outbox.OutboxRelay
import jp.xhw.mikke.platform.outbox.RedisOutboxPublisher
import jp.xhw.mikke.platform.redis.RedisStreamConsumerGroup
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.post.v1.PostServiceGrpcKt
import jp.xhw.mikke.services.guess.application.GuessService
import jp.xhw.mikke.services.guess.infrastructure.*
import jp.xhw.mikke.services.guess.worker.PostCreatedHandler
import jp.xhw.mikke.services.guess.worker.PostDeletedHandler
import jp.xhw.mikke.services.guess.worker.ProcessedEventStoreGate
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    val database = connectMariaDbFromEnv(defaultDatabase = "guess_service")
    val redis = connectRedisFromEnv()
    val transactionRunner = ExposedTransactionRunner(database)
    val tokenService = JwtTokenService(secret = System.getenv("IDENTITY_JWT_SECRET") ?: "dev-identity-secret")
    val instanceId = guessServiceInstanceId()

    val postChannel =
        grpcClientChannelFromEnvironment(
            targetEnv = "POST_SERVICE_TARGET",
            hostEnv = "POST_SERVICE_HOST",
            portEnv = "POST_SERVICE_PORT",
            defaultHost = "post-service",
            defaultPort = 50053,
        )
    val postStub =
        PostServiceGrpcKt
            .PostServiceCoroutineStub(postChannel)
            .withGuessServiceClientInterceptors()

    val guessApplicationService =
        GuessService(
            guessRepository = ExposedGuessRepository(),
            guessUserStatsRepository = ExposedGuessUserStatsRepository(),
            postAuthorStatsRepository = ExposedPostAuthorStatsRepository(),
            guessOutboxRepository = ExposedGuessOutboxRepository(),
            postAccessPort = GrpcPostAccessAdapter(postStub),
            transactionRunner = transactionRunner,
        )
    val guessServiceRpc = GuessServiceRpc(guessService = guessApplicationService)

    val guessEventsStream = System.getenv("GUESS_EVENTS_STREAM") ?: "mikke.guess.events"
    val postEventsStream = System.getenv("POST_EVENTS_STREAM") ?: "mikke.post.events"
    val postEventsDeadLetterStream = System.getenv("POST_EVENTS_DEAD_LETTER_STREAM") ?: "$postEventsStream.dead"

    val outboxRelay =
        OutboxRelay(
            publisher =
                RedisOutboxPublisher(
                    outboxTable = GuessOutboxTable,
                    transactionRunner = transactionRunner,
                    producer =
                        RedisStreamProducer(
                            commands = redis.connection.sync(),
                            streamName = guessEventsStream,
                        ),
                    producerName = "guess-service",
                    publisherId = System.getenv("OUTBOX_PUBLISHER_ID") ?: "guess-service-$instanceId",
                    batchSize = System.getenv("OUTBOX_RELAY_BATCH_SIZE")?.toIntOrNull() ?: 100,
                    leaseDuration = System.getenv("OUTBOX_RELAY_LEASE_SECONDS")?.toLongOrNull()?.seconds ?: 30.seconds,
                ),
            idleDelay = System.getenv("OUTBOX_RELAY_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds ?: 500.milliseconds,
            errorDelay = System.getenv("OUTBOX_RELAY_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds ?: 5.seconds,
        )
    outboxRelay.start()

    val processedEventStore = ProcessedEventStoreGate(ProcessedEventStore(GuessProcessedEventsTable))
    val postAuthorStatsRepository = ExposedPostAuthorStatsRepository()
    val postEventSubscription =
        RedisEventSubscription(
            consumerGroup =
                RedisStreamConsumerGroup(
                    commands = redis.connection.sync(),
                    streamName = postEventsStream,
                    consumerGroup = System.getenv("GUESS_POST_EVENTS_CONSUMER_GROUP") ?: "guess-service",
                    consumerName = System.getenv("GUESS_POST_EVENTS_CONSUMER_NAME") ?: "guess-service-$instanceId",
                ),
            handlers =
                listOf(
                    EventHandlerRegistration(
                        eventType = PostEventTypes.CREATED,
                        eventVersion = 1,
                        payloadSerializer = PostCreatedPayload.serializer(),
                        handler =
                            PostCreatedHandler(
                                postAuthorStatsRepository = postAuthorStatsRepository,
                                transactionRunner = transactionRunner,
                                processedEventStore = processedEventStore,
                            ),
                    ),
                    EventHandlerRegistration(
                        eventType = PostEventTypes.DELETED,
                        eventVersion = 1,
                        payloadSerializer = PostDeletedPayload.serializer(),
                        handler =
                            PostDeletedHandler(
                                postAuthorStatsRepository = postAuthorStatsRepository,
                                transactionRunner = transactionRunner,
                                processedEventStore = processedEventStore,
                            ),
                    ),
                ),
            deadLetterSink =
                RedisDeadLetterSink(
                    RedisStreamProducer(
                        commands = redis.connection.sync(),
                        streamName = postEventsDeadLetterStream,
                    ),
                ),
            startId = System.getenv("GUESS_POST_EVENTS_CONSUMER_GROUP_START_ID") ?: "0-0",
            maxDeliveryAttempts = System.getenv("GUESS_POST_EVENTS_MAX_DELIVERY_ATTEMPTS")?.toIntOrNull() ?: 10,
            readCount = System.getenv("GUESS_POST_EVENTS_READ_COUNT")?.toLongOrNull() ?: 10,
            staleMinIdle =
                Duration.ofSeconds(
                    System.getenv("GUESS_POST_EVENTS_STALE_MIN_IDLE_SECONDS")?.toLongOrNull() ?: 300,
                ),
            idleDelay =
                System.getenv("GUESS_POST_EVENTS_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: 500.milliseconds,
            errorDelay =
                System.getenv("GUESS_POST_EVENTS_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: 5.seconds,
        )
    postEventSubscription.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            postEventSubscription.stop()
            outboxRelay.stop()
            redis.close()
            postChannel.shutdownGracefully()
        },
    )

    grpcServer(
        serviceName = "guess-service",
        portEnv = "GUESS_SERVICE_PORT",
        defaultPort = 50055,
        exceptionHandling =
            GrpcServerExceptionHandling(
                internalErrorDescription = "Internal guess service error",
                domainExceptionMapper = { throwable -> throwable.toGuessGrpcStatus() },
            ),
    ) {
        installGrpcHealth(serviceName = "guess-service")
        intercept(InternalRpcServerInterceptor())
        intercept(OutboundBearerTokenCaptureInterceptor())
        intercept(
            GrpcAuthServerInterceptor(
                authenticator = { headers ->
                    headers.bearerToken()?.let(tokenService::authenticateAccessToken)
                },
                optional = false,
                methodAuthPolicies =
                    mapOf(
                        HealthGrpc.getCheckMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
                        HealthGrpc.getWatchMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
                    ),
            ),
        )
        addService(guessServiceRpc)
    }.startAndAwait()
}

private fun guessServiceInstanceId(): String =
    listOfNotNull(
        System.getenv("HOSTNAME"),
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
    ).firstOrNull { it.isNotBlank() }
        ?: "unknown-host-${ProcessHandle.current().pid()}"

private fun ManagedChannel.shutdownGracefully() {
    shutdown()
    if (!awaitTermination(5, TimeUnit.SECONDS)) {
        shutdownNow()
    }
}
