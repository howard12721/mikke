package jp.xhw.mikke.api.user.application

import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.common.application.normalized
import jp.xhw.mikke.api.common.application.requireText
import jp.xhw.mikke.api.graphql.ApiRequestContext

class UserApiService(
    private val userGateway: UserGateway,
) {
    suspend fun me(context: ApiRequestContext): CurrentUser = userGateway.me(context)

    suspend fun getUser(
        context: ApiRequestContext,
        userId: String,
    ): PublicUser = userGateway.getUser(context, userId.requireText("userId"))

    suspend fun searchUsers(
        context: ApiRequestContext,
        query: String,
        page: PageInput,
    ): PageResult<PublicUser> = userGateway.searchUsers(context, query.requireText("query"), page.normalized())

    suspend fun updateProfile(
        context: ApiRequestContext,
        username: String?,
        displayName: String?,
        avatarMediaId: String?,
    ): CurrentUser =
        userGateway.updateProfile(
            context = context,
            username = username?.trim()?.takeIf { it.isNotEmpty() },
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
            avatarMediaId = avatarMediaId?.trim()?.takeIf { it.isNotEmpty() },
        )

    suspend fun changePassword(
        context: ApiRequestContext,
        currentPassword: String,
        newPassword: String,
    ) {
        userGateway.changePassword(
            context,
            currentPassword.requireText("currentPassword"),
            newPassword.requireText("newPassword"),
        )
    }
}
