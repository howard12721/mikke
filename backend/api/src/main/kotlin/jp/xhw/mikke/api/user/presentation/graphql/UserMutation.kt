package jp.xhw.mikke.api.user.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.user.application.UserApiService

class UserMutation(
    private val userApiService: UserApiService,
) : Mutation {
    suspend fun updateProfile(
        input: UpdateProfileInput,
        environment: DataFetchingEnvironment,
    ): CurrentUser =
        userApiService
            .updateProfile(
                context = environment.apiRequestContext(),
                username = input.username,
                displayName = input.displayName,
                avatarMediaId = input.avatarMediaId,
            ).toGraphQl()

    suspend fun changePassword(
        input: ChangePasswordInput,
        environment: DataFetchingEnvironment,
    ): ChangePasswordPayload {
        userApiService.changePassword(
            context = environment.apiRequestContext(),
            currentPassword = input.currentPassword,
            newPassword = input.newPassword,
        )
        return ChangePasswordPayload(success = true)
    }
}
