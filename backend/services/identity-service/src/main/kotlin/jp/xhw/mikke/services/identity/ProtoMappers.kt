package jp.xhw.mikke.services.identity

import jp.xhw.mikke.common.v1.PageInfo
import jp.xhw.mikke.identity.v1.AuthSession
import jp.xhw.mikke.identity.v1.PublicUser
import jp.xhw.mikke.identity.v1.User
import jp.xhw.mikke.identity.v1.UserStatus
import jp.xhw.mikke.platform.auth.IssuedAuthSession
import jp.xhw.mikke.platform.pagination.PageSlice
import jp.xhw.mikke.platform.time.toProtoTimestamp
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.identity.model.IdentityUser
import jp.xhw.mikke.services.identity.model.IdentityUserStatus

fun IdentityUser.toProto(): User =
    User
        .newBuilder()
        .setId(formatGrpcUuid(id.value))
        .setEmail(email.value)
        .setUsername(username.value)
        .setDisplayName(displayName.value)
        .setStatus(status.toProto())
        .setCreatedAt(createdAt.toProtoTimestamp())
        .setUpdatedAt(updatedAt.toProtoTimestamp())
        .apply {
            this@toProto.avatarMediaId?.let { mediaId -> setAvatarMediaId(formatGrpcUuid(mediaId.value)) }
        }.build()

fun IdentityUser.toPublicProto(): PublicUser =
    PublicUser
        .newBuilder()
        .setId(formatGrpcUuid(id.value))
        .setUsername(username.value)
        .setDisplayName(displayName.value)
        .setStatus(status.toProto())
        .apply {
            this@toPublicProto.avatarMediaId?.let { mediaId -> setAvatarMediaId(formatGrpcUuid(mediaId.value)) }
        }.build()

fun IssuedAuthSession.toProto(): AuthSession =
    AuthSession
        .newBuilder()
        .setAccessToken(accessToken.value)
        .setRefreshToken(refreshToken.value)
        .setAccessTokenExpiresAt(accessToken.expiresAt.toProtoTimestamp())
        .setRefreshTokenExpiresAt(refreshToken.expiresAt.toProtoTimestamp())
        .build()

fun PageSlice<IdentityUser>.toPageInfo(): PageInfo =
    PageInfo
        .newBuilder()
        .setHasNextPage(hasNextPage)
        .apply {
            nextPageToken?.let { setNextPageToken(it) }
        }.build()

private fun IdentityUserStatus.toProto(): UserStatus =
    when (this) {
        IdentityUserStatus.ACTIVE -> UserStatus.USER_STATUS_ACTIVE
        IdentityUserStatus.SUSPENDED -> UserStatus.USER_STATUS_SUSPENDED
        IdentityUserStatus.DEACTIVATED -> UserStatus.USER_STATUS_DEACTIVATED
    }
