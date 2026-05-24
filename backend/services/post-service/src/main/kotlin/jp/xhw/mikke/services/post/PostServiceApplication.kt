package jp.xhw.mikke.services.post

import io.grpc.ManagedChannel
import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.friendship.v1.FriendshipServiceGrpcKt
import jp.xhw.mikke.identity.v1.IdentityServiceGrpcKt
import jp.xhw.mikke.media.v1.MediaServiceGrpcKt
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthServerInterceptor
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
import jp.xhw.mikke.platform.auth.grpc.bearerToken
import jp.xhw.mikke.platform.auth.jwt.JwtTokenService
import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.grpc.GrpcServerExceptionHandling
import jp.xhw.mikke.platform.grpc.InternalCallerClientInterceptor
import jp.xhw.mikke.platform.grpc.InternalRpcServerInterceptor
import jp.xhw.mikke.platform.grpc.grpcClientChannelFromEnvironment
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait
import jp.xhw.mikke.platform.outbox.OutboxRelay
import jp.xhw.mikke.platform.outbox.RedisOutboxPublisher
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.post.v1.PostServiceGrpc
import jp.xhw.mikke.services.post.application.PostService
import jp.xhw.mikke.services.post.infrastructure.ExposedPostOutboxRepository
import jp.xhw.mikke.services.post.infrastructure.ExposedPostRepository
import jp.xhw.mikke.services.post.infrastructure.GrpcPostMediaChecker
import jp.xhw.mikke.services.post.infrastructure.GrpcPostUserStatusChecker
import jp.xhw.mikke.services.post.infrastructure.GrpcPostVisibilityAuthorizer
import jp.xhw.mikke.services.post.infrastructure.PostOutboxTable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    val database = connectMariaDbFromEnv(defaultDatabase = "post_service")
    val redis = connectRedisFromEnv()
    val transactionRunner = ExposedTransactionRunner(database)
    val tokenService = JwtTokenService(secret = System.getenv("IDENTITY_JWT_SECRET") ?: "dev-identity-secret")

    val identityChannel =
        grpcClientChannelFromEnvironment(
            targetEnv = "IDENTITY_SERVICE_TARGET",
            hostEnv = "IDENTITY_SERVICE_HOST",
            portEnv = "IDENTITY_SERVICE_PORT",
            defaultHost = "identity-service",
            defaultPort = 50051,
        )
    val friendshipChannel =
        grpcClientChannelFromEnvironment(
            targetEnv = "FRIENDSHIP_SERVICE_TARGET",
            hostEnv = "FRIENDSHIP_SERVICE_HOST",
            portEnv = "FRIENDSHIP_SERVICE_PORT",
            defaultHost = "friendship-service",
            defaultPort = 50052,
        )
    val mediaChannel =
        grpcClientChannelFromEnvironment(
            targetEnv = "MEDIA_SERVICE_TARGET",
            hostEnv = "MEDIA_SERVICE_HOST",
            portEnv = "MEDIA_SERVICE_PORT",
            defaultHost = "media-service",
            defaultPort = 50054,
        )

    val friendshipStub =
        FriendshipServiceGrpcKt
            .FriendshipServiceCoroutineStub(friendshipChannel)
            .withInterceptors(InternalCallerClientInterceptor(serviceName = "post-service"))

    val postApplicationService =
        PostService(
            postRepository = ExposedPostRepository(),
            outboxRepository = ExposedPostOutboxRepository(),
            mediaChecker =
                GrpcPostMediaChecker(
                    MediaServiceGrpcKt.MediaServiceCoroutineStub(mediaChannel),
                ),
            visibilityAuthorizer = GrpcPostVisibilityAuthorizer(friendshipStub),
            userStatusChecker =
                GrpcPostUserStatusChecker(
                    IdentityServiceGrpcKt.IdentityServiceCoroutineStub(identityChannel),
                ),
            transactionRunner = transactionRunner,
        )
    val postService = PostServiceRpc(postService = postApplicationService)

    val outboxRelay =
        OutboxRelay(
            publisher =
                RedisOutboxPublisher(
                    outboxTable = PostOutboxTable,
                    transactionRunner = transactionRunner,
                    producer =
                        RedisStreamProducer(
                            commands = redis.connection.sync(),
                            streamName = System.getenv("POST_EVENTS_STREAM") ?: "mikke.post.events",
                        ),
                    producerName = "post-service",
                    publisherId = System.getenv("OUTBOX_PUBLISHER_ID") ?: "post-service-${ProcessHandle.current().pid()}",
                    batchSize = System.getenv("OUTBOX_RELAY_BATCH_SIZE")?.toIntOrNull() ?: 100,
                    leaseDuration = System.getenv("OUTBOX_RELAY_LEASE_SECONDS")?.toLongOrNull()?.seconds ?: 30.seconds,
                ),
            idleDelay = System.getenv("OUTBOX_RELAY_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds ?: 500.milliseconds,
            errorDelay = System.getenv("OUTBOX_RELAY_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds ?: 5.seconds,
        )
    outboxRelay.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            outboxRelay.stop()
            redis.close()
            listOf(identityChannel, friendshipChannel, mediaChannel).forEach { it.shutdownGracefully() }
        },
    )

    grpcServer(
        serviceName = "post-service",
        portEnv = "POST_SERVICE_PORT",
        defaultPort = 50053,
        exceptionHandling =
            GrpcServerExceptionHandling(
                internalErrorDescription = "Internal post service error",
                domainExceptionMapper = { throwable -> throwable.toPostGrpcStatus() },
            ),
    ) {
        installGrpcHealth(serviceName = "post-service")
        val methodAuthPolicies = postGrpcAuthPolicies()
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
        addService(postService)
    }.startAndAwait()
}

fun postGrpcAuthPolicies(): Map<String, GrpcEndpointAuthPolicy> =
    mapOf(
        HealthGrpc.getCheckMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
        HealthGrpc.getWatchMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
        PostServiceGrpc.getGetPostLocationForGuessMethod().fullMethodName to GrpcEndpointAuthPolicy.InternalRequired,
    )

private fun ManagedChannel.shutdownGracefully() {
    shutdown()
    if (!awaitTermination(5, TimeUnit.SECONDS)) {
        shutdownNow()
    }
}
