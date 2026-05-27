package jp.xhw.mikke.api.auth.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.auth.application.*
import jp.xhw.mikke.api.graphql.apiRequestContext

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

    suspend fun logout(environment: DataFetchingEnvironment): LogoutPayload {
        authApiService.logout(environment.apiRequestContext())
        return LogoutPayload(success = true)
    }
}
