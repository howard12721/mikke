package jp.xhw.mikke.api.user.application

import jp.xhw.mikke.api.common.application.*
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.graphql.requireAuthenticatedActor
import jp.xhw.mikke.api.media.application.MediaGateway

class UserApiService(
    private val userGateway: UserGateway,
    private val mediaGateway: MediaGateway,
) {
    suspend fun me(context: ApiRequestContext): CurrentUser {
        context.requireAuthenticatedActor()
        val user = userGateway.me(context)
        val avatarUrlByMediaId = resolveAvatarUrls(context, listOfNotNull(user.avatarMediaId))
        return user.withAvatarUrl(avatarUrlByMediaId[user.avatarMediaId])
    }

    suspend fun getUser(
        context: ApiRequestContext,
        userId: String,
    ): PublicUser {
        val user = userGateway.getUser(context, userId.requireText("userId"))
        val avatarUrlByMediaId = resolveAvatarUrls(context, listOfNotNull(user.avatarMediaId))
        return user.withAvatarUrl(avatarUrlByMediaId[user.avatarMediaId])
    }

    suspend fun batchGetUsers(
        context: ApiRequestContext,
        userIds: List<String>,
    ): List<PublicUser> {
        val users = userGateway.batchGetUsers(context, userIds.map { it.requireText("userIds") }.distinct())
        val avatarUrlByMediaId = resolveAvatarUrls(context, users.mapNotNull { it.avatarMediaId })
        return users.map { it.withAvatarUrl(avatarUrlByMediaId[it.avatarMediaId]) }
    }

    suspend fun batchGetUsersByIdMap(
        context: ApiRequestContext,
        userIds: List<String>,
    ): Map<String, PublicUser> {
        val requestedUserIds = userIds.map { it.requireText("userIds") }.distinct()
        val users = userGateway.batchGetUsers(context, requestedUserIds)
        val avatarUrlByMediaId = resolveAvatarUrls(context, users.mapNotNull { it.avatarMediaId })
        val usersById = users.map { it.withAvatarUrl(avatarUrlByMediaId[it.avatarMediaId]) }.associateBy { it.id }
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
    ): PageResult<PublicUser> {
        val users = userGateway.searchUsers(context, query.requireText("query"), page.normalized())
        val avatarUrlByMediaId = resolveAvatarUrls(context, users.items.mapNotNull { it.avatarMediaId })
        return users.copy(items = users.items.map { it.withAvatarUrl(avatarUrlByMediaId[it.avatarMediaId]) })
    }

    suspend fun updateProfile(
        context: ApiRequestContext,
        username: String?,
        displayName: String?,
        avatarMediaId: String?,
    ): CurrentUser {
        context.requireAuthenticatedActor()
        val user =
            userGateway.updateProfile(
                context = context,
                username = username?.trim()?.takeIf { it.isNotEmpty() },
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
                avatarMediaId = avatarMediaId?.trim()?.takeIf { it.isNotEmpty() }?.requireUuidText("avatarMediaId"),
            )
        val avatarUrlByMediaId = resolveAvatarUrls(context, listOfNotNull(user.avatarMediaId))
        return user.withAvatarUrl(avatarUrlByMediaId[user.avatarMediaId])
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

    private suspend fun resolveAvatarUrls(
        context: ApiRequestContext,
        avatarMediaIds: List<String>,
    ): Map<String, String> {
        if (avatarMediaIds.isEmpty()) {
            return emptyMap()
        }
        return mediaGateway
            .batchGetMedia(context, avatarMediaIds.distinct())
            .associate { media ->
                val avatarUrl = media.iconUrl ?: media.originalUrl
                media.id to avatarUrl
            }
    }
}

private fun PublicUser.withAvatarUrl(avatarUrl: String?): PublicUser = copy(avatarUrl = avatarUrl)

private fun CurrentUser.withAvatarUrl(avatarUrl: String?): CurrentUser = copy(avatarUrl = avatarUrl)
