package jp.xhw.mikke.api.auth.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import jp.xhw.mikke.api.auth.application.*

class AuthMutation(
    private val authApiService: AuthApiService,
) : Mutation {
    suspend fun login(input: LoginInput): AuthPayload =
        authApiService
            .login(
                LoginCommand(
                    loginId = input.loginId,
                    password = input.password,
                ),
            ).toGraphQl()

    suspend fun register(input: RegisterInput): AuthPayload =
        authApiService
            .register(
                RegisterCommand(
                    email = input.email,
                    username = input.username,
                    displayName = input.displayName,
                    password = input.password,
                ),
            ).toGraphQl()

    suspend fun refresh(input: RefreshInput): RefreshAuthPayload =
        authApiService.refresh(RefreshCommand(refreshToken = input.refreshToken)).toGraphQl()

    suspend fun logout(input: LogoutInput): LogoutPayload {
        authApiService.logout(LogoutCommand(refreshToken = input.refreshToken))
        return LogoutPayload(success = true)
    }
}
