package jp.xhw.mikke.api.auth.presentation.graphql

data class LoginInput(
    val loginId: String,
    val password: String,
)

data class RegisterInput(
    val email: String,
    val username: String,
    val displayName: String,
    val password: String,
)

data class AuthPayload(
    val session: AuthSession,
)

data class LogoutPayload(
    val success: Boolean,
)

data class AuthSession(
    val sessionId: String,
    val idleExpiresAt: String,
    val absoluteExpiresAt: String,
)
