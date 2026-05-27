package jp.xhw.mikke.api.user.application

import jp.xhw.mikke.api.common.application.*
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.graphql.requireAuthenticatedActor

class UserApiService(
    private val userGateway: UserGateway,
) {
    suspend fun me(context: ApiRequestContext): CurrentUser {
        context.requireAuthenticatedActor()
        return userGateway.me(context)
    }

    suspend fun getUser(
        context: ApiRequestContext,
        userId: String,
    ): PublicUser = userGateway.getUser(context, userId.requireText("userId"))

    suspend fun batchGetUsers(
        context: ApiRequestContext,
        userIds: List<String>,
    ): List<PublicUser> = userGateway.batchGetUsers(context, userIds.map { it.requireText("userIds") }.distinct())

    suspend fun batchGetUsersByIdMap(
        context: ApiRequestContext,
        userIds: List<String>,
    ): Map<String, PublicUser> {
        val requestedUserIds = userIds.map { it.requireText("userIds") }.distinct()
        val usersById = userGateway.batchGetUsers(context, requestedUserIds).associateBy { it.id }
        val missingUserIds = requestedUserIds.filterNot(usersById::containsKey)
        check(missingUserIds.isEmpty()) {
            "User API did not return requested users: userIds=${missingUserIds.joinToString(",")}"
        }
        return usersById
    }

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
    ): CurrentUser {
        context.requireAuthenticatedActor()
        return userGateway.updateProfile(
            context = context,
            username = username?.trim()?.takeIf { it.isNotEmpty() },
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
            avatarMediaId = avatarMediaId?.trim()?.takeIf { it.isNotEmpty() }?.requireUuidText("avatarMediaId"),
        )
    }

    suspend fun changePassword(
        context: ApiRequestContext,
        currentPassword: String,
        newPassword: String,
    ) {
        context.requireAuthenticatedActor()
        userGateway.changePassword(
            context,
            currentPassword.requireText("currentPassword"),
            newPassword.requireText("newPassword"),
        )
    }
}
