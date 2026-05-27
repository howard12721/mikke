package jp.xhw.mikke.api.user.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.common.infrastructure.call
import jp.xhw.mikke.api.common.infrastructure.toPageInfo
import jp.xhw.mikke.api.common.infrastructure.toProto
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.graphql.requireAuthenticatedActor
import jp.xhw.mikke.api.infrastructure.closeChannel
import jp.xhw.mikke.api.infrastructure.gatewayChannelFromEnvironment
import jp.xhw.mikke.api.infrastructure.toIsoString
import jp.xhw.mikke.api.infrastructure.toProto
import jp.xhw.mikke.api.infrastructure.withInternalAuth
import jp.xhw.mikke.api.user.application.CurrentUser
import jp.xhw.mikke.api.user.application.PublicUser
import jp.xhw.mikke.api.user.application.UserGateway
import jp.xhw.mikke.identity.v1.*
import jp.xhw.mikke.identity.v1.PublicUser as ProtoPublicUser

class GrpcUserGateway(
    private val channel: ManagedChannel,
    private val stub: IdentityServiceGrpcKt.IdentityServiceCoroutineStub =
        IdentityServiceGrpcKt.IdentityServiceCoroutineStub(channel).withInternalAuth(),
) : UserGateway {
    override suspend fun me(context: ApiRequestContext): CurrentUser =
        call {
            stub
                .getMe(
                    GetMeRequest
                        .newBuilder()
                        .setActor(context.requireAuthenticatedActor().toProto())
                        .build(),
                ).user
                .toCurrentUser()
        }

    override suspend fun getUser(
        context: ApiRequestContext,
        userId: String,
    ): PublicUser =
        call {
            stub
                .getUser(GetUserRequest.newBuilder().setUserId(userId).build())
                .user
                .toPublicUser()
        }

    override suspend fun batchGetUsers(
        context: ApiRequestContext,
        userIds: List<String>,
    ): List<PublicUser> =
        if (userIds.isEmpty()) {
            emptyList()
        } else {
            call {
                stub
                    .batchGetUsers(BatchGetUsersRequest.newBuilder().addAllUserIds(userIds).build())
                    .usersList
                    .map { it.toPublicUser() }
            }
        }

    override suspend fun searchUsers(
        context: ApiRequestContext,
        query: String,
        page: PageInput,
    ): PageResult<PublicUser> =
        call {
            val response =
                stub.searchUsers(
                    SearchUsersRequest
                        .newBuilder()
                        .setQuery(query)
                        .setPage(page.toProto())
                        .build(),
                )
            PageResult(response.usersList.map { it.toPublicUser() }, response.pageInfo.toPageInfo())
        }

    override suspend fun updateProfile(
        context: ApiRequestContext,
        username: String?,
        displayName: String?,
        avatarMediaId: String?,
    ): CurrentUser =
        call {
            val builder =
                UpdateProfileRequest
                    .newBuilder()
                    .setActor(context.requireAuthenticatedActor().toProto())
            username?.let(builder::setUsername)
            displayName?.let(builder::setDisplayName)
            avatarMediaId?.let(builder::setAvatarMediaId)
            stub.updateProfile(builder.build()).user.toCurrentUser()
        }

    override suspend fun changePassword(
        context: ApiRequestContext,
        currentPassword: String,
        newPassword: String,
    ) {
        call {
            stub.changePassword(
                ChangePasswordRequest
                    .newBuilder()
                    .setActor(context.requireAuthenticatedActor().toProto())
                    .setCurrentPassword(currentPassword)
                    .setNewPassword(newPassword)
                    .build(),
            )
        }
    }

    override fun close() = closeChannel(channel)

    companion object {
        fun fromEnvironment(): GrpcUserGateway =
            GrpcUserGateway(
                gatewayChannelFromEnvironment(
                    targetEnv = "IDENTITY_SERVICE_TARGET",
                    hostEnv = "IDENTITY_SERVICE_HOST",
                    portEnv = "IDENTITY_SERVICE_PORT",
                    defaultPort = 50051,
                ),
            )
    }
}

private fun User.toCurrentUser(): CurrentUser =
    CurrentUser(
        id = id,
        email = email,
        username = username,
        displayName = displayName,
        status = status.name.removePrefix("USER_STATUS_"),
        createdAt = createdAt.toIsoString(),
        updatedAt = updatedAt.toIsoString(),
        avatarMediaId = avatarMediaId.takeIf { it.isNotEmpty() },
    )

private fun ProtoPublicUser.toPublicUser(): PublicUser =
    PublicUser(
        id = id,
        username = username,
        displayName = displayName,
        status = status.name.removePrefix("USER_STATUS_"),
        avatarMediaId = avatarMediaId.takeIf { it.isNotEmpty() },
    )
