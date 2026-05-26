package jp.xhw.mikke.services.identity

import io.grpc.Status
import jp.xhw.mikke.identity.v1.*
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthContext
import jp.xhw.mikke.platform.grpc.currentAuthenticatedUser
import jp.xhw.mikke.platform.grpc.withGrpcExceptionMapping
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.services.identity.application.command.ChangePasswordCommand
import jp.xhw.mikke.services.identity.application.command.LoginIdentityUserCommand
import jp.xhw.mikke.services.identity.application.command.RegisterIdentityUserCommand
import jp.xhw.mikke.services.identity.application.command.UpdateProfileCommand
import jp.xhw.mikke.services.identity.application.exception.IdentityApplicationException
import jp.xhw.mikke.services.identity.application.input.parseAvatarMediaIdOrNull
import jp.xhw.mikke.services.identity.application.input.parseUserId
import jp.xhw.mikke.services.identity.application.pagination.SearchUsersCursor
import jp.xhw.mikke.services.identity.application.service.IdentityService
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("identity-service")

class IdentityServiceRpc(
    private val identityService: IdentityService,
) : IdentityServiceGrpcKt.IdentityServiceCoroutineImplBase() {
    override suspend fun registerUser(request: RegisterUserRequest): RegisterUserResponse =
        mapRpcExceptions {
            val result =
                identityService.register(
                    RegisterIdentityUserCommand(
                        email = request.email.requireField("email"),
                        username = request.username.requireField("username"),
                        displayName = request.displayName.requireField("display_name"),
                        password = request.password.requireField("password"),
                    ),
                )

            RegisterUserResponse
                .newBuilder()
                .setUser(result.user.toProto())
                .setSession(result.session.toProto())
                .build()
        }

    override suspend fun loginUser(request: LoginUserRequest): LoginUserResponse =
        mapRpcExceptions {
            val result =
                identityService.login(
                    LoginIdentityUserCommand(
                        loginId = request.loginId.requireField("login_id"),
                        password = request.password.requireField("password"),
                    ),
                )

            LoginUserResponse
                .newBuilder()
                .setUser(result.user.toProto())
                .setSession(result.session.toProto())
                .build()
        }

    override suspend fun refreshSession(request: RefreshSessionRequest): RefreshSessionResponse =
        mapRpcExceptions {
            val session = identityService.refreshSession(request.refreshToken.requireField("refresh_token"))

            RefreshSessionResponse
                .newBuilder()
                .setSession(session.toProto())
                .build()
        }

    override suspend fun logoutSession(request: LogoutSessionRequest): LogoutSessionResponse =
        mapRpcExceptions {
            identityService.logout(request.refreshToken.requireField("refresh_token"))

            LogoutSessionResponse.getDefaultInstance()
        }

    override suspend fun getMe(request: GetMeRequest): GetMeResponse =
        mapRpcExceptions {
            val principal =
                GrpcAuthContext.currentPrincipal()
                    ?: throw Status.UNAUTHENTICATED.withDescription("Authentication required").asRuntimeException()

            val user = identityService.getMe(principal.subject)

            GetMeResponse
                .newBuilder()
                .setUser(user.toProto())
                .build()
        }

    override suspend fun getUser(request: GetUserRequest): GetUserResponse =
        mapRpcExceptions {
            val user = identityService.getUser(parseUserId(request.userId.requireField("user_id")))

            GetUserResponse
                .newBuilder()
                .setUser(user.toPublicProto())
                .build()
        }

    override suspend fun batchGetUsers(request: BatchGetUsersRequest): BatchGetUsersResponse =
        mapRpcExceptions {
            val users =
                identityService.batchGetUsers(
                    request.userIdsList.map { parseUserId(it.requireField("user_id")) },
                )

            BatchGetUsersResponse
                .newBuilder()
                .addAllUsers(users.map { it.toPublicProto() })
                .build()
        }

    override suspend fun searchUsers(request: SearchUsersRequest): SearchUsersResponse =
        mapRpcExceptions {
            val page =
                PageRequestInput(
                    pageSize = request.page.pageSize,
                    pageToken = request.page.pageToken,
                ).validate<SearchUsersCursor>(
                    cursorDecoder = SearchUsersCursor::decode,
                )

            val result =
                identityService.searchUsers(
                    query = request.query.requireField("query"),
                    page = page,
                )

            SearchUsersResponse
                .newBuilder()
                .addAllUsers(result.items.map { it.toPublicProto() })
                .setPageInfo(result.toPageInfo())
                .build()
        }

    override suspend fun updateProfile(request: UpdateProfileRequest): UpdateProfileResponse =
        mapRpcExceptions {
            val user =
                currentAuthenticatedUser().let { userId ->
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

            UpdateProfileResponse
                .newBuilder()
                .setUser(user.toProto())
                .build()
        }

    override suspend fun deactivateAccount(request: DeactivateAccountRequest): DeactivateAccountResponse =
        mapRpcExceptions {
            identityService.deactivateAccount(currentAuthenticatedUser().toString())

            DeactivateAccountResponse.getDefaultInstance()
        }

    override suspend fun changePassword(request: ChangePasswordRequest): ChangePasswordResponse =
        mapRpcExceptions {
            identityService.changePassword(
                subject = currentAuthenticatedUser().toString(),
                command =
                    ChangePasswordCommand(
                        currentPassword = request.currentPassword.requireField("current_password"),
                        newPassword = request.newPassword.requireField("new_password"),
                    ),
            )

            ChangePasswordResponse.getDefaultInstance()
        }
}

private suspend inline fun <T> mapRpcExceptions(block: suspend () -> T): T =
    withGrpcExceptionMapping(
        logger = logger,
        serviceName = "identity-service",
        internalErrorDescription = "Internal identity service error",
        domainExceptionMapper = { candidate ->
            (candidate as? IdentityApplicationException)?.toGrpcStatus()
        },
        block = block,
    )

private fun String.requireField(fieldName: String): String =
    trim().takeIf { it.isNotEmpty() }
        ?: throw Status.INVALID_ARGUMENT.withDescription("$fieldName is required").asRuntimeException()
