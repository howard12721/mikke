package jp.xhw.mikke.services.post

import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.friendship.v1.FriendshipServiceGrpcKt
import jp.xhw.mikke.identity.v1.IdentityServiceGrpcKt
import jp.xhw.mikke.media.v1.MediaServiceGrpcKt
import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.grpc.*
import jp.xhw.mikke.platform.outbox.OutboxRelay
import jp.xhw.mikke.platform.outbox.RedisOutboxPublisher
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.post.v1.PostServiceGrpc
import jp.xhw.mikke.services.post.application.PostService
import jp.xhw.mikke.services.post.infrastructure.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    val database = connectMariaDbFromEnv(defaultDatabase = "post_service")
    val redis = connectRedisFromEnv()
    val transactionRunner = ExposedTransactionRunner(database)

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
                    MediaServiceGrpcKt
                        .MediaServiceCoroutineStub(mediaChannel)
                        .withInterceptors(InternalCallerClientInterceptor(serviceName = "post-service")),
                ),
            visibilityAuthorizer = GrpcPostVisibilityAuthorizer(friendshipStub),
            userStatusChecker =
                GrpcPostUserStatusChecker(
                    IdentityServiceGrpcKt
                        .IdentityServiceCoroutineStub(identityChannel)
                        .withInterceptors(InternalCallerClientInterceptor(serviceName = "post-service")),
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
                    publisherId =
                        System.getenv("OUTBOX_PUBLISHER_ID") ?: "post-service-${
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
        intercept(InternalRpcServerInterceptor(methodAuthPolicies = postGrpcAuthPolicies()))
        addService(postService)
    }.startAndAwait()
}

fun postGrpcAuthPolicies(): Map<String, jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy> {
    val internalRequired =
        listOf(
            PostServiceGrpc.getCreatePostMethod(),
            PostServiceGrpc.getGetPostMethod(),
            PostServiceGrpc.getBatchGetPostsMethod(),
            PostServiceGrpc.getListVisiblePostsMethod(),
            PostServiceGrpc.getListUserPostsMethod(),
            PostServiceGrpc.getListMyPostsMethod(),
            PostServiceGrpc.getDeletePostMethod(),
            PostServiceGrpc.getUpdatePostCaptionMethod(),
        ).associate { method ->
            method.fullMethodName to
                jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
                    .internalRequired("api")
        }

    return internalRequired +
        mapOf(
            PostServiceGrpc.getCheckPostVisibilityMethod().fullMethodName to
                jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
                    .internalRequired("api", "guess-service"),
            PostServiceGrpc.getGetPostLocationForGuessMethod().fullMethodName to
                jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
                    .internalRequired("guess-service"),
            HealthGrpc.getCheckMethod().fullMethodName to jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy.UserOptional,
            HealthGrpc.getWatchMethod().fullMethodName to jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy.UserOptional,
        )
}

private fun io.grpc.ManagedChannel.shutdownGracefully() {
    shutdown()
    if (!awaitTermination(5, TimeUnit.SECONDS)) {
        shutdownNow()
    }
}
