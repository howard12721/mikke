package jp.xhw.mikke.api.auth.application

interface IdentityAuthGateway : AutoCloseable {
    suspend fun login(command: LoginCommand): LoginResult

    suspend fun register(command: RegisterCommand): RegisterResult

    suspend fun logout(command: LogoutCommand)

    override fun close() {}
}
