package jp.xhw.mikke.services.identity

import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.grpc.GrpcServerExceptionHandling
import jp.xhw.mikke.platform.grpc.InternalRpcServerInterceptor
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.services.identity.application.exception.IdentityApplicationException
import jp.xhw.mikke.services.identity.application.security.PasswordHasher
import jp.xhw.mikke.services.identity.application.service.IdentityService
import jp.xhw.mikke.services.identity.infrastructure.ExposedIdentityUserRepository
import jp.xhw.mikke.services.identity.infrastructure.RedisClientSessionStore
import jp.xhw.mikke.services.identity.infrastructure.outbox.ExposedIdentityUserOutbox

fun main() {
    val passwordHasher = PasswordHasher()
    val database = connectMariaDbFromEnv(defaultDatabase = "identity_service")
    val userRepository = ExposedIdentityUserRepository()
    val userOutbox = ExposedIdentityUserOutbox()
    val transactionRunner = ExposedTransactionRunner(database)
    val redis = connectRedisFromEnv()
    val clientSessionStore = RedisClientSessionStore(commands = redis.connection.sync())
    val identityApplicationService =
        IdentityService(
            userRepository = userRepository,
            clientSessionStore = clientSessionStore,
            userOutbox = userOutbox,
            transactionRunner = transactionRunner,
            passwordHasher = passwordHasher,
        )
    val identityService = IdentityServiceRpc(identityService = identityApplicationService)
    startIdentityOutboxRelay(transactionRunner)

    grpcServer(
        serviceName = "identity-service",
        portEnv = "IDENTITY_SERVICE_PORT",
        defaultPort = 50051,
        exceptionHandling =
            GrpcServerExceptionHandling(
                internalErrorDescription = "Internal identity service error",
                domainExceptionMapper =
                    { throwable ->
                        (throwable as? IdentityApplicationException)?.toGrpcStatus()
                    },
            ),
    ) {
        installGrpcHealth(serviceName = "identity-service")
        intercept(InternalRpcServerInterceptor(methodAuthPolicies = identityGrpcAuthPolicies()))
        addService(identityService)
    }.startAndAwait()
}
