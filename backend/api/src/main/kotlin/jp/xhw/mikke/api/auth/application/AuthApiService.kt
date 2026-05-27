package jp.xhw.mikke.api.auth.application

import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.graphql.requireAuthenticatedActor
import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException
import jp.xhw.mikke.platform.auth.PasswordPolicy

class AuthApiService(
    private val identityAuthGateway: IdentityAuthGateway,
) {
    suspend fun login(command: LoginCommand): LoginResult {
        val loginId = command.loginId.trim()
        if (loginId.isEmpty()) {
            throw ApiHttpException(
                status = ApiErrorCode.InvalidRequest.status,
                message = "loginId is required",
            )
        }
        if (command.password.isBlank()) {
            throw ApiHttpException(
                status = ApiErrorCode.InvalidRequest.status,
                message = "password is required",
            )
        }

        return identityAuthGateway.login(
            command = command.copy(loginId = loginId),
        )
    }

    suspend fun register(command: RegisterCommand): RegisterResult {
        val email = command.email.trim()
        val username = command.username.trim()
        val password = command.password.trim()
        val displayName = command.displayName.trim()
        if (email.isEmpty()) {
            throw ApiHttpException(
                status = ApiErrorCode.InvalidRequest.status,
                message = "email is required",
            )
        }
        if (username.isEmpty()) {
            throw ApiHttpException(
                status = ApiErrorCode.InvalidRequest.status,
                message = "username is required",
            )
        }
        if (displayName.isEmpty()) {
            throw ApiHttpException(
                status = ApiErrorCode.InvalidRequest.status,
                message = "displayName is required",
            )
        }
        if (password.isEmpty()) {
            throw ApiHttpException(
                status = ApiErrorCode.InvalidRequest.status,
                message = "password is required",
            )
        }

        try {
            PasswordPolicy.validate(password)
        } catch (e: IllegalArgumentException) {
            throw ApiHttpException(
                status = ApiErrorCode.InvalidRequest.status,
                message = e.message ?: "password is invalid",
            )
        }

        return identityAuthGateway.register(
            command =
                command.copy(
                    email = email,
                    username = username,
                    displayName = displayName,
                    password = password,
                ),
        )
    }

    suspend fun logout(context: ApiRequestContext) {
        val actor = context.requireAuthenticatedActor()
        identityAuthGateway.logout(LogoutCommand(sessionHash = actor.sessionHash))
    }
}

data class LoginCommand(
    val loginId: String,
    val password: String,
)

data class LoginResult(
    val user: AuthenticatedUser,
    val session: AuthSession,
)

data class RegisterCommand(
    val email: String,
    val username: String,
    val displayName: String,
    val password: String,
)

data class RegisterResult(
    val user: AuthenticatedUser,
    val session: AuthSession,
)

data class LogoutCommand(
    val sessionHash: String,
)

data class AuthenticatedUser(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

data class AuthSession(
    val sessionId: String,
    val idleExpiresAt: String,
    val absoluteExpiresAt: String,
)
