package jp.xhw.mikke.services.identity

import jp.xhw.mikke.platform.auth.grpc.GrpcAuthServerInterceptor
import jp.xhw.mikke.platform.auth.grpc.bearerToken
import jp.xhw.mikke.platform.auth.jwt.JwtTokenService
import jp.xhw.mikke.platform.database.connectMariaDbFromEnv
import jp.xhw.mikke.platform.database.exposed.ExposedTransactionRunner
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait
import jp.xhw.mikke.services.identity.application.ExposedIdentityUserOutbox
import jp.xhw.mikke.services.identity.application.IdentityService
import jp.xhw.mikke.services.identity.application.PasswordHasher
import jp.xhw.mikke.services.identity.application.RefreshSessionTokenService
import jp.xhw.mikke.services.identity.infrastructure.ExposedIdentityUserRepository
import jp.xhw.mikke.services.identity.infrastructure.ExposedRefreshSessionRepository

fun main() {
    val passwordHasher = PasswordHasher()
    val database = connectMariaDbFromEnv(defaultDatabase = "identity_service")
    val userRepository = ExposedIdentityUserRepository()
    val refreshSessionRepository = ExposedRefreshSessionRepository()
    val userOutbox = ExposedIdentityUserOutbox()
    val transactionRunner = ExposedTransactionRunner(database)
    val tokenService = JwtTokenService(secret = System.getenv("IDENTITY_JWT_SECRET") ?: "dev-identity-secret")
    val refreshSessionTokenService = RefreshSessionTokenService()
    val identityApplicationService =
        IdentityService(
            userRepository = userRepository,
            refreshSessionRepository = refreshSessionRepository,
            userOutbox = userOutbox,
            transactionRunner = transactionRunner,
            passwordHasher = passwordHasher,
            tokenService = tokenService,
            refreshSessionTokenService = refreshSessionTokenService,
        )
    val identityService = IdentityServiceRpc(identityService = identityApplicationService)

    grpcServer(serviceName = "identity-service", portEnv = "IDENTITY_SERVICE_PORT", defaultPort = 50051) {
        installGrpcHealth(serviceName = "identity-service")
        intercept(
            GrpcAuthServerInterceptor(
                authenticator = { headers ->
                    headers.bearerToken()?.let(tokenService::authenticateAccessToken)
                },
                optional = true,
            ),
        )
        addService(identityService)
    }.startAndAwait()
}
