package jp.xhw.mikke.services.friendship

import jp.xhw.mikke.friendship.v1.FriendshipServiceGrpc
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
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
    val tokenService = JwtTokenService(secret = System.getenv("IDENTITY_JWT_SECRET") ?: "dev-identity-secret")
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
        val methodAuthPolicies =
            mapOf(
                FriendshipServiceGrpc.getCheckCanViewUserPostsForViewerMethod().fullMethodName to
                    GrpcEndpointAuthPolicy.InternalRequired,
            )
        intercept(InternalRpcServerInterceptor(methodAuthPolicies = methodAuthPolicies))
        intercept(
            GrpcAuthServerInterceptor(
                authenticator = { headers ->
                    headers.bearerToken()?.let(tokenService::authenticateAccessToken)
                },
                optional = false,
                methodAuthPolicies = methodAuthPolicies,
            ),
        )
        addService(friendshipService)
    }.startAndAwait()
}
