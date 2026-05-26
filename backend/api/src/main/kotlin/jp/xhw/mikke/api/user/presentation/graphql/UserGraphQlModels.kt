package jp.xhw.mikke.api.user.presentation.graphql

import jp.xhw.mikke.api.common.presentation.graphql.PageInfo

data class User(
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

data class UserSearchPayload(
    val users: List<User>,
    val pageInfo: PageInfo,
)

data class UpdateProfileInput(
    val username: String? = null,
    val displayName: String? = null,
    val avatarMediaId: String? = null,
)

data class ChangePasswordInput(
    val currentPassword: String,
    val newPassword: String,
)

data class ChangePasswordPayload(
    val success: Boolean,
)

fun jp.xhw.mikke.api.user.application.PublicUser.toGraphQl(): User =
    User(id = id, username = username, displayName = displayName, status = status, avatarMediaId = avatarMediaId)

fun jp.xhw.mikke.api.user.application.CurrentUser.toGraphQl(): CurrentUser =
    CurrentUser(
        id = id,
        email = email,
        username = username,
        displayName = displayName,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        avatarMediaId = avatarMediaId,
    )
