package jp.xhw.mikke.services.identity

import io.grpc.Status
import jp.xhw.mikke.identity.v1.*
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthContext
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.grpc.currentAuthenticatedUser
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.services.identity.application.command.LoginIdentityUserCommand
import jp.xhw.mikke.services.identity.application.command.RegisterIdentityUserCommand
import jp.xhw.mikke.services.identity.application.command.UpdateProfileCommand
import jp.xhw.mikke.services.identity.application.exception.*
import jp.xhw.mikke.services.identity.application.input.parseAvatarMediaIdOrNull
import jp.xhw.mikke.services.identity.application.input.parseUserId
import jp.xhw.mikke.services.identity.application.pagination.SearchUsersCursor
import jp.xhw.mikke.services.identity.application.service.IdentityService

class IdentityServiceRpc(
    private val identityService: IdentityService,
) : IdentityServiceGrpcKt.IdentityServiceCoroutineImplBase() {
    override suspend fun registerUser(request: RegisterUserRequest): RegisterUserResponse {
        val result =
            execute {
                identityService.register(
                    RegisterIdentityUserCommand(
                        email = request.email.requireField("email"),
                        username = request.username.requireField("username"),
                        displayName = request.displayName.requireField("display_name"),
                        password = request.password.requireField("password"),
                    ),
                )
            }

        return RegisterUserResponse
            .newBuilder()
            .setUser(result.user.toProto())
            .setSession(result.session.toProto())
            .build()
    }

    override suspend fun loginUser(request: LoginUserRequest): LoginUserResponse {
        val result =
            execute {
                identityService.login(
                    LoginIdentityUserCommand(
                        loginId = request.loginId.requireField("login_id"),
                        password = request.password.requireField("password"),
                    ),
                )
            }

        return LoginUserResponse
            .newBuilder()
            .setUser(result.user.toProto())
            .setSession(result.session.toProto())
            .build()
    }

    override suspend fun refreshSession(request: RefreshSessionRequest): RefreshSessionResponse {
        val session = execute { identityService.refreshSession(request.refreshToken.requireField("refresh_token")) }

        return RefreshSessionResponse
            .newBuilder()
            .setSession(session.toProto())
            .build()
    }

    override suspend fun logoutSession(request: LogoutSessionRequest): LogoutSessionResponse {
        execute { identityService.logout(request.refreshToken.requireField("refresh_token")) }

        return LogoutSessionResponse.getDefaultInstance()
    }

    override suspend fun getMe(request: GetMeRequest): GetMeResponse {
        val principal =
            GrpcAuthContext.currentPrincipal()
                ?: throw Status.UNAUTHENTICATED.withDescription("Authentication required").asRuntimeException()

        val user = execute { identityService.getMe(principal.subject) }

        return GetMeResponse
            .newBuilder()
            .setUser(user.toProto())
            .build()
    }

    override suspend fun getUser(request: GetUserRequest): GetUserResponse {
        val user = execute { identityService.getUser(parseUserId(request.userId.requireField("user_id"))) }

        return GetUserResponse
            .newBuilder()
            .setUser(user.toPublicProto())
            .build()
    }

    override suspend fun batchGetUsers(request: BatchGetUsersRequest): BatchGetUsersResponse {
        val users =
            execute {
                identityService.batchGetUsers(
                    request.userIdsList.map { parseUserId(it.requireField("user_id")) },
                )
            }

        return BatchGetUsersResponse
            .newBuilder()
            .addAllUsers(users.map { it.toPublicProto() })
            .build()
    }

    override suspend fun searchUsers(request: SearchUsersRequest): SearchUsersResponse {
        val page =
            execute {
                PageRequestInput(
                    pageSize = request.page.pageSize,
                    pageToken = request.page.pageToken,
                ).validate<SearchUsersCursor>(
                    cursorDecoder = SearchUsersCursor::decode,
                )
            }

        val result =
            execute {
                identityService.searchUsers(
                    query = request.query.requireField("query"),
                    page = page,
                )
            }

        return SearchUsersResponse
            .newBuilder()
            .addAllUsers(result.items.map { it.toPublicProto() })
            .setPageInfo(result.toPageInfo())
            .build()
    }

    override suspend fun updateProfile(request: UpdateProfileRequest): UpdateProfileResponse {
        val userId = currentAuthenticatedUser()

        val user =
            execute {
                identityService.updateProfile(
                    subject = userId.toString(),
                    command =
                        UpdateProfileCommand(
                            username = request.username.takeIf { it.isNotBlank() },
                            displayName = request.displayName.takeIf { it.isNotBlank() },
                            avatarMediaId = parseAvatarMediaIdOrNull(request.avatarMediaId),
                        ),
                )
            }

        return UpdateProfileResponse
            .newBuilder()
            .setUser(user.toProto())
            .build()
    }

    override suspend fun deactivateAccount(request: DeactivateAccountRequest): DeactivateAccountResponse {
        val userId = currentAuthenticatedUser()

        execute { identityService.deactivateAccount(userId.toString()) }

        return DeactivateAccountResponse.getDefaultInstance()
    }
}

private fun String.requireField(fieldName: String): String =
    trim().takeIf { it.isNotEmpty() }
        ?: throw Status.INVALID_ARGUMENT.withDescription("$fieldName is required").asRuntimeException()

private inline fun <T> execute(block: () -> T): T =
    try {
        block()
    } catch (e: IdentityApplicationException) {
        throw e.toStatus().asRuntimeException()
    } catch (e: ValidationException) {
        throw Status.INVALID_ARGUMENT.withDescription(e.message).asRuntimeException()
    }

private fun IdentityApplicationException.toStatus(): Status =
    when (this) {
        is InvalidIdentityInputException -> Status.INVALID_ARGUMENT.withDescription(message)
        is DuplicateIdentityUserException -> Status.ALREADY_EXISTS.withDescription(message)
        is InvalidCredentialsException -> Status.UNAUTHENTICATED.withDescription(message)
        is InvalidRefreshTokenException -> Status.UNAUTHENTICATED.withDescription(message)
        is UserNotFoundException -> Status.NOT_FOUND.withDescription(message)
    }
