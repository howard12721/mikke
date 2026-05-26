package jp.xhw.mikke.api.auth.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.auth.application.*
import jp.xhw.mikke.api.auth.application.AuthSession
import jp.xhw.mikke.api.infrastructure.closeChannel
import jp.xhw.mikke.api.infrastructure.gatewayChannelFromEnvironment
import jp.xhw.mikke.api.infrastructure.grpcGatewayCall
import jp.xhw.mikke.api.infrastructure.toIsoString
import jp.xhw.mikke.identity.v1.*
import jp.xhw.mikke.identity.v1.AuthSession as ProtoAuthSession

class GrpcIdentityAuthGateway(
    private val channel: ManagedChannel,
    private val stub: IdentityServiceGrpcKt.IdentityServiceCoroutineStub =
        IdentityServiceGrpcKt.IdentityServiceCoroutineStub(
            channel,
        ),
) : IdentityAuthGateway {
    override suspend fun login(command: LoginCommand): LoginResult =
        grpcGatewayCall {
            stub.loginUser(command.toRequestModel()).toLoginResult()
        }

    override suspend fun register(command: RegisterCommand): RegisterResult =
        grpcGatewayCall {
            stub.registerUser(command.toRequestModel()).toRegisterResult()
        }

    override suspend fun refresh(command: RefreshCommand): RefreshResult =
        grpcGatewayCall {
            stub.refreshSession(command.toRequestModel()).toRefreshResult()
        }

    override suspend fun logout(command: LogoutCommand) {
        grpcGatewayCall {
            stub.logoutSession(command.toRequestModel())
        }
    }

    override fun close() = closeChannel(channel)

    companion object {
        fun fromEnvironment(): GrpcIdentityAuthGateway =
            GrpcIdentityAuthGateway(
                channel =
                    gatewayChannelFromEnvironment(
                        targetEnv = "IDENTITY_SERVICE_TARGET",
                        hostEnv = "IDENTITY_SERVICE_HOST",
                        portEnv = "IDENTITY_SERVICE_PORT",
                        defaultPort = 50051,
                    ),
            )
    }
}

private fun LoginCommand.toRequestModel(): LoginUserRequest =
    LoginUserRequest
        .newBuilder()
        .setLoginId(loginId)
        .setPassword(password)
        .build()

private fun LoginUserResponse.toLoginResult(): LoginResult =
    LoginResult(
        user = user.toAuthenticatedUser(),
        session = session.toAuthSession(),
    )

private fun RegisterCommand.toRequestModel(): RegisterUserRequest =
    RegisterUserRequest
        .newBuilder()
        .setEmail(email)
        .setUsername(username)
        .setDisplayName(displayName)
        .setPassword(password)
        .build()

private fun RegisterUserResponse.toRegisterResult(): RegisterResult =
    RegisterResult(
        user = user.toAuthenticatedUser(),
        session = session.toAuthSession(),
    )

private fun RefreshCommand.toRequestModel(): RefreshSessionRequest =
    RefreshSessionRequest
        .newBuilder()
        .setRefreshToken(refreshToken)
        .build()

private fun RefreshSessionResponse.toRefreshResult(): RefreshResult =
    RefreshResult(
        session = session.toAuthSession(),
    )

private fun LogoutCommand.toRequestModel(): LogoutSessionRequest =
    LogoutSessionRequest
        .newBuilder()
        .setRefreshToken(refreshToken)
        .build()

private fun User.toAuthenticatedUser(): AuthenticatedUser =
    AuthenticatedUser(
        id = id,
        email = email,
        username = username,
        displayName = displayName,
        status = status.toApiStatus(),
        createdAt = createdAt.toIsoString(),
        updatedAt = updatedAt.toIsoString(),
    )

private fun ProtoAuthSession.toAuthSession(): AuthSession =
    AuthSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        accessTokenExpiresAt = accessTokenExpiresAt.toIsoString(),
        refreshTokenExpiresAt = refreshTokenExpiresAt.toIsoString(),
    )

private fun UserStatus.toApiStatus(): String =
    when (this) {
        UserStatus.USER_STATUS_ACTIVE -> "active"

        UserStatus.USER_STATUS_SUSPENDED -> "suspended"

        UserStatus.USER_STATUS_DEACTIVATED -> "deactivated"

        UserStatus.USER_STATUS_UNSPECIFIED,
        UserStatus.UNRECOGNIZED,
        -> "unspecified"
    }
