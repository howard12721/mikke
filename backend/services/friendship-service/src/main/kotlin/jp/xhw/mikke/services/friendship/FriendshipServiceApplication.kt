package jp.xhw.mikke.services.friendship

import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.friendship.v1.FriendshipServiceGrpc
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.grpc.*
import jp.xhw.mikke.platform.outbox.OutboxRelay
import jp.xhw.mikke.platform.outbox.RedisOutboxPublisher
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.services.friendship.application.exception.FriendshipApplicationException
import jp.xhw.mikke.services.friendship.application.service.FriendshipService
import jp.xhw.mikke.services.friendship.infrastructure.ExposedBlockRepository
import jp.xhw.mikke.services.friendship.infrastructure.ExposedFriendRequestRepository
import jp.xhw.mikke.services.friendship.infrastructure.ExposedFriendshipRepository
import jp.xhw.mikke.services.friendship.infrastructure.outbox.ExposedFriendshipOutbox
import jp.xhw.mikke.services.friendship.infrastructure.outbox.FriendshipOutboxTable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    val database = connectMariaDbFromEnv(defaultDatabase = "friendship_service")
    val redis = connectRedisFromEnv()
    val friendRequestRepository = ExposedFriendRequestRepository()
    val friendshipRepository = ExposedFriendshipRepository()
    val blockRepository = ExposedBlockRepository()
    val friendshipOutbox = ExposedFriendshipOutbox()
    val transactionRunner = ExposedTransactionRunner(database)
    val friendshipApplicationService =
        FriendshipService(
            friendRequestRepository = friendRequestRepository,
            friendshipRepository = friendshipRepository,
            blockRepository = blockRepository,
            friendshipOutbox = friendshipOutbox,
            transactionRunner = transactionRunner,
        )
    val friendshipService = FriendshipServiceRpc(friendshipService = friendshipApplicationService)
    val outboxRelay =
        OutboxRelay(
            publisher =
                RedisOutboxPublisher(
                    outboxTable = FriendshipOutboxTable,
                    transactionRunner = transactionRunner,
                    producer =
                        RedisStreamProducer(
                            commands = redis.connection.sync(),
                            streamName = System.getenv("DOMAIN_EVENTS_STREAM") ?: "mikke.events",
                        ),
                    producerName = "friendship-service",
                    publisherId =
                        System.getenv("OUTBOX_PUBLISHER_ID") ?: "friendship-service-${
                            ProcessHandle.current().pid()
                        }",
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
        serviceName = "friendship-service",
        portEnv = "FRIENDSHIP_SERVICE_PORT",
        defaultPort = 50052,
        exceptionHandling =
            GrpcServerExceptionHandling(
                internalErrorDescription = "Internal friendship service error",
                domainExceptionMapper =
                    { throwable ->
                        (throwable as? FriendshipApplicationException)?.toGrpcStatus()
                    },
            ),
    ) {
        installGrpcHealth(serviceName = "friendship-service")
        intercept(InternalRpcServerInterceptor(methodAuthPolicies = friendshipGrpcAuthPolicies()))
        addService(friendshipService)
    }.startAndAwait()
}

fun friendshipGrpcAuthPolicies(): Map<String, GrpcEndpointAuthPolicy> {
    val internalRequired =
        listOf(
            FriendshipServiceGrpc.getSendFriendRequestMethod(),
            FriendshipServiceGrpc.getAcceptFriendRequestMethod(),
            FriendshipServiceGrpc.getRejectFriendRequestMethod(),
            FriendshipServiceGrpc.getCancelFriendRequestMethod(),
            FriendshipServiceGrpc.getRemoveFriendMethod(),
            FriendshipServiceGrpc.getBlockUserMethod(),
            FriendshipServiceGrpc.getUnblockUserMethod(),
            FriendshipServiceGrpc.getGetFriendshipMethod(),
            FriendshipServiceGrpc.getListFriendsMethod(),
            FriendshipServiceGrpc.getListIncomingFriendRequestsMethod(),
            FriendshipServiceGrpc.getListOutgoingFriendRequestsMethod(),
            FriendshipServiceGrpc.getBatchGetFriendshipSummariesMethod(),
        ).associate { method -> method.fullMethodName to GrpcEndpointAuthPolicy.internalRequired("api") }

    return internalRequired +
        mapOf(
            FriendshipServiceGrpc.getCheckCanViewUserPostsMethod().fullMethodName to
                GrpcEndpointAuthPolicy.internalRequired("api", "post-service", "media-service"),
            FriendshipServiceGrpc.getCheckCanViewUserPostsForViewerMethod().fullMethodName to
                GrpcEndpointAuthPolicy.internalRequired("api", "post-service", "media-service"),
            HealthGrpc.getCheckMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
            HealthGrpc.getWatchMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
        )
}
