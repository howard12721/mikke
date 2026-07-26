package jp.xhw.mikke.services.notification

import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.events.post.PostCreatedPayload
import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.notification.v1.NotificationServiceGrpc
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.events.subscription.EventHandlerRegistration
import jp.xhw.mikke.platform.events.subscription.RedisDeadLetterSink
import jp.xhw.mikke.platform.events.subscription.RedisEventSubscription
import jp.xhw.mikke.platform.grpc.GrpcServerExceptionHandling
import jp.xhw.mikke.platform.grpc.InternalRpcServerInterceptor
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait
import jp.xhw.mikke.platform.redis.RedisStreamConsumerGroup
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.services.notification.application.ExposedPostNotificationEnqueuer
import jp.xhw.mikke.services.notification.application.PushRegistrationCipher
import jp.xhw.mikke.services.notification.application.PushRegistrationService
import jp.xhw.mikke.services.notification.infrastructure.ExposedPushDeliveryRepository
import jp.xhw.mikke.services.notification.infrastructure.ExposedPushRegistrationRepository
import jp.xhw.mikke.services.notification.infrastructure.FirebasePushSender
import jp.xhw.mikke.services.notification.infrastructure.GrpcFriendshipReader
import jp.xhw.mikke.services.notification.worker.PostCreatedNotificationHandler
import jp.xhw.mikke.services.notification.worker.PushDeliveryWorker
import java.net.InetAddress
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    val database = connectMariaDbFromEnv(defaultDatabase = "notification_service")
    val redis = connectRedisFromEnv()
    val transactionRunner = ExposedTransactionRunner(database)
    val registrationCipher = PushRegistrationCipher.fromEnvironment()
    val instanceId = notificationServiceInstanceId()

    val friendshipReader = GrpcFriendshipReader.fromEnvironment()
    val pushRegistrationService =
        PushRegistrationService(
            repository = ExposedPushRegistrationRepository(),
            transactionRunner = transactionRunner,
            cipher = registrationCipher,
        )
    val notificationServiceRpc =
        NotificationServiceRpc(pushRegistrationService = pushRegistrationService)

    val postEventsStream = System.getenv("POST_EVENTS_STREAM") ?: "mikke.post.events"
    val postEventsDeadLetterStream =
        System.getenv("POST_EVENTS_DEAD_LETTER_STREAM") ?: "$postEventsStream.notification.dead"
    val postEventSubscription =
        RedisEventSubscription(
            consumerGroup =
                RedisStreamConsumerGroup(
                    commands = redis.connection.sync(),
                    streamName = postEventsStream,
                    consumerGroup =
                        System.getenv("NOTIFICATION_POST_EVENTS_CONSUMER_GROUP")
                            ?: "notification-service",
                    consumerName =
                        System.getenv("NOTIFICATION_POST_EVENTS_CONSUMER_NAME")
                            ?: "notification-service-$instanceId",
                ),
            handlers =
                listOf(
                    EventHandlerRegistration(
                        eventType = PostEventTypes.CREATED,
                        eventVersion = 1,
                        payloadSerializer = PostCreatedPayload.serializer(),
                        handler =
                            PostCreatedNotificationHandler(
                                friendshipReader = friendshipReader,
                                notificationEnqueuer =
                                    ExposedPostNotificationEnqueuer(
                                        transactionRunner = transactionRunner,
                                    ),
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
            ignoredEventTypes =
                setOf(
                    PostEventTypes.DELETED,
                    PostEventTypes.CAPTION_UPDATED,
                    PostEventTypes.VISIBILITY_UPDATED,
                ),
            startId = System.getenv("NOTIFICATION_POST_EVENTS_CONSUMER_GROUP_START_ID") ?: "$",
            maxDeliveryAttempts =
                System.getenv("NOTIFICATION_POST_EVENTS_MAX_DELIVERY_ATTEMPTS")?.toIntOrNull()
                    ?: 10,
            readCount = System.getenv("NOTIFICATION_POST_EVENTS_READ_COUNT")?.toLongOrNull() ?: 10,
            staleMinIdle =
                Duration.ofSeconds(
                    System.getenv("NOTIFICATION_POST_EVENTS_STALE_MIN_IDLE_SECONDS")?.toLongOrNull()
                        ?: 300,
                ),
            idleDelay =
                System.getenv("NOTIFICATION_POST_EVENTS_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: 500.milliseconds,
            errorDelay =
                System.getenv("NOTIFICATION_POST_EVENTS_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: 5.seconds,
        )
    postEventSubscription.start()

    val firebasePushSender = FirebasePushSender.fromEnvironmentOrNull()
    val pushDeliveryWorker =
        firebasePushSender?.let { sender ->
            PushDeliveryWorker(
                workerId = "notification-service-$instanceId",
                repository = ExposedPushDeliveryRepository(),
                transactionRunner = transactionRunner,
                cipher = registrationCipher,
                sender = sender,
                batchSize = System.getenv("PUSH_DELIVERY_BATCH_SIZE")?.toIntOrNull() ?: 100,
                maxAttempts = System.getenv("PUSH_DELIVERY_MAX_ATTEMPTS")?.toIntOrNull() ?: 10,
                leaseDuration =
                    System.getenv("PUSH_DELIVERY_LEASE_SECONDS")?.toLongOrNull()?.seconds
                        ?: 30.seconds,
                idleDelay =
                    System.getenv("PUSH_DELIVERY_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                        ?: 500.milliseconds,
                errorDelay =
                    System.getenv("PUSH_DELIVERY_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                        ?: 5.seconds,
            ).also(PushDeliveryWorker::start)
        }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            postEventSubscription.stop()
            pushDeliveryWorker?.stop()
            firebasePushSender?.close()
            friendshipReader.close()
            redis.close()
        },
    )

    grpcServer(
        serviceName = "notification-service",
        portEnv = "NOTIFICATION_SERVICE_PORT",
        defaultPort = 50057,
        exceptionHandling = GrpcServerExceptionHandling("Internal notification service error"),
    ) {
        installGrpcHealth(serviceName = "notification-service")
        intercept(InternalRpcServerInterceptor(methodAuthPolicies = notificationGrpcAuthPolicies()))
        addService(notificationServiceRpc)
    }.startAndAwait()
}

fun notificationGrpcAuthPolicies(): Map<String, GrpcEndpointAuthPolicy> {
    val apiMethods =
        listOf(
            NotificationServiceGrpc.getListNotificationsMethod(),
            NotificationServiceGrpc.getGetNotificationMethod(),
            NotificationServiceGrpc.getMarkNotificationReadMethod(),
            NotificationServiceGrpc.getMarkAllNotificationsReadMethod(),
            NotificationServiceGrpc.getGetUnreadNotificationCountMethod(),
            NotificationServiceGrpc.getGetNotificationPreferencesMethod(),
            NotificationServiceGrpc.getUpdateNotificationPreferencesMethod(),
            NotificationServiceGrpc.getRegisterPushTokenMethod(),
            NotificationServiceGrpc.getDeletePushTokenMethod(),
        ).associate { method -> method.fullMethodName to GrpcEndpointAuthPolicy.internalRequired("api") }

    return apiMethods +
        mapOf(
            HealthGrpc.getCheckMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
            HealthGrpc.getWatchMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
        )
}

private fun notificationServiceInstanceId(): String =
    listOfNotNull(
        System.getenv("HOSTNAME"),
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
    ).firstOrNull { it.isNotBlank() }
        ?: "unknown-host-${ProcessHandle.current().pid()}"
