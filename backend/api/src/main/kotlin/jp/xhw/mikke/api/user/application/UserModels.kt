package jp.xhw.mikke.api.user.application

import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.graphql.ApiRequestContext

data class PublicUser(
    val id: String,
    val username: String,
    val displayName: String,
    val status: String,
    val avatarMediaId: String?,
)

data class CurrentUser(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val avatarMediaId: String?,
)

interface UserGateway : AutoCloseable {
    suspend fun me(context: ApiRequestContext): CurrentUser

    suspend fun getUser(
        context: ApiRequestContext,
        userId: String,
    ): PublicUser

    suspend fun batchGetUsers(
        context: ApiRequestContext,
        userIds: List<String>,
    ): List<PublicUser>

    suspend fun searchUsers(
        context: ApiRequestContext,
        query: String,
        page: PageInput,
    ): PageResult<PublicUser>

    suspend fun updateProfile(
        context: ApiRequestContext,
        username: String?,
        displayName: String?,
        avatarMediaId: String?,
    ): CurrentUser
}
