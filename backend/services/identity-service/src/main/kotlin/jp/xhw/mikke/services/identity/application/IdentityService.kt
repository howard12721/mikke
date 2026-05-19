package jp.xhw.mikke.services.identity.application

import jp.xhw.mikke.platform.auth.AuthenticatedPrincipal
import jp.xhw.mikke.platform.auth.IssuedAuthSession
import jp.xhw.mikke.platform.auth.IssuedToken
import jp.xhw.mikke.platform.auth.PasswordPolicy
import jp.xhw.mikke.platform.auth.jwt.JwtTokenService
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.pagination.PageSlice
import jp.xhw.mikke.platform.pagination.ValidatedPageRequest
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.platform.uuid.parseGrpcUuidOrNull
import jp.xhw.mikke.services.identity.model.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityService(
    private val userRepository: IdentityUserRepository,
    private val refreshSessionRepository: RefreshSessionRepository,
    private val userOutbox: IdentityUserOutbox,
    private val transactionRunner: TransactionRunner,
    private val passwordHasher: PasswordHasher,
    private val tokenService: JwtTokenService,
    private val refreshSessionTokenService: RefreshSessionTokenService = RefreshSessionTokenService(),
    private val clock: Clock = Clock.System,
) {
    fun register(command: RegisterIdentityUserCommand): AuthenticatedIdentityUser =
        transactionRunner.runInTransaction {
            try {
                val now = clock.now()
                PasswordPolicy.validate(command.password)
                val user =
                    IdentityUser(
                        id = UserId(Uuid.random()),
                        email = Email(command.email.normalizeEmail()),
                        username = Username(command.username.trim()),
                        displayName = DisplayName(command.displayName.trim()),
                        passwordHash = passwordHasher.hash(command.password),
                        avatarMediaId = null,
                        status = IdentityUserStatus.ACTIVE,
                        createdAt = now,
                        updatedAt = now,
                        deactivatedAt = null,
                    )
                userRepository.saveUser(user)
                userOutbox.appendUserCreated(user)

                AuthenticatedIdentityUser(
                    user = user,
                    session = issueAuthSession(user.id, now),
                )
            } catch (e: IllegalArgumentException) {
                throw InvalidIdentityInputException(message = e.message ?: "invalid input", cause = e)
            }
        }

    fun login(command: LoginIdentityUserCommand): AuthenticatedIdentityUser {
        val user =
            transactionRunner.runInTransaction {
                userRepository.findByLogin(command.loginId)
                    ?: throw InvalidCredentialsException()
            }

        if (!user.canAuthenticate()) {
            throw InvalidCredentialsException()
        }

        val passwordMatches =
            runCatching { passwordHasher.verify(command.password, user.passwordHash) }
                .getOrDefault(false)

        if (!passwordMatches) {
            throw InvalidCredentialsException()
        }

        val session = transactionRunner.runInTransaction { issueAuthSession(user.id, clock.now()) }

        return AuthenticatedIdentityUser(user = user, session = session)
    }

    fun refreshSession(refreshToken: String): IssuedAuthSession {
        val now = clock.now()
        val refreshTokenHash = refreshSessionTokenService.hash(refreshToken)

        return transactionRunner.runInTransaction {
            val currentSession =
                refreshSessionRepository
                    .findByRefreshTokenHash(refreshTokenHash)
                    ?.takeIf { it.isActiveAt(now) }
                    ?: throw InvalidRefreshTokenException()

            val user =
                userRepository.findByIds(listOf(currentSession.userId)).firstOrNull()
                    ?: throw InvalidRefreshTokenException()

            if (!user.canAuthenticate()) {
                throw InvalidRefreshTokenException()
            }

            val revoked = refreshSessionRepository.revoke(currentSession.id, now)
            if (!revoked) {
                throw InvalidRefreshTokenException()
            }

            issueAuthSession(user.id, now)
        }
    }

    fun logout(refreshToken: String) {
        val now = clock.now()
        val refreshTokenHash = refreshSessionTokenService.hash(refreshToken)

        transactionRunner.runInTransaction {
            refreshSessionRepository.revokeByRefreshTokenHash(refreshTokenHash, now)
        }
    }

    fun getMe(subject: String): IdentityUser {
        val userId =
            subject.toUserIdOrNull()
                ?: throw UserNotFoundException()

        return transactionRunner.runInTransaction {
            userRepository.findByIds(listOf(userId)).firstOrNull()
                ?: throw UserNotFoundException()
        }
    }

    fun getUser(userId: UserId): IdentityUser =
        transactionRunner.runInTransaction {
            userRepository
                .findByIds(listOf(userId))
                .firstOrNull()
                ?.takeIf { it.isPubliclyVisible() }
                ?: throw UserNotFoundException()
        }

    fun batchGetUsers(userIds: List<UserId>): List<IdentityUser> =
        transactionRunner.runInTransaction {
            if (userIds.isEmpty()) {
                return@runInTransaction emptyList()
            }

            userRepository
                .findByIds(userIds)
                .filter { it.isPubliclyVisible() }
        }

    fun searchUsers(
        query: String,
        page: ValidatedPageRequest<SearchUsersCursor>,
    ): PageSlice<IdentityUser> =
        transactionRunner.runInTransaction {
            val normalizedQuery = query.trim().normalizeUsername()
            if (normalizedQuery.isEmpty()) {
                throw InvalidIdentityInputException("query must not be blank")
            }

            val fetchLimit = page.limit + 1
            val matches =
                userRepository.searchByUsernamePrefix(
                    normalizedPrefix = normalizedQuery,
                    limit = fetchLimit,
                    cursor = page.cursor,
                )

            val hasNextPage = matches.size > page.limit
            val pageItems = if (hasNextPage) matches.take(page.limit) else matches
            val nextPageToken =
                if (hasNextPage) {
                    val last = pageItems.last()
                    SearchUsersCursor.encode(
                        SearchUsersCursor(
                            normalizedUsername = last.username.value.normalizeUsername(),
                            id = last.id.value,
                        ),
                    )
                } else {
                    null
                }

            PageSlice(
                items = pageItems,
                nextPageToken = nextPageToken,
                hasNextPage = hasNextPage,
            )
        }

    fun updateProfile(
        subject: String,
        command: UpdateProfileCommand,
    ): IdentityUser =
        transactionRunner.runInTransaction {
            val userId =
                subject.toUserIdOrNull()
                    ?: throw UserNotFoundException()

            val current =
                userRepository.findByIds(listOf(userId)).firstOrNull()
                    ?: throw UserNotFoundException()

            if (!current.canAuthenticate()) {
                throw UserNotFoundException()
            }

            val now = clock.now()
            val updated =
                try {
                    current.copy(
                        username = command.username?.let { Username(it.trim()) } ?: current.username,
                        displayName = command.displayName?.let { DisplayName(it.trim()) } ?: current.displayName,
                        avatarMediaId = command.avatarMediaId ?: current.avatarMediaId,
                        updatedAt = now,
                    )
                } catch (e: IllegalArgumentException) {
                    throw InvalidIdentityInputException(message = e.message ?: "invalid input", cause = e)
                }

            if (updated == current) {
                return@runInTransaction current
            }

            userRepository.updateProfile(updated)
            userOutbox.appendProfileUpdated(updated)
            updated
        }

    fun deactivateAccount(subject: String) {
        transactionRunner.runInTransaction {
            val userId =
                subject.toUserIdOrNull()
                    ?: throw UserNotFoundException()

            val current =
                userRepository.findByIds(listOf(userId)).firstOrNull()
                    ?: throw UserNotFoundException()

            if (current.status == IdentityUserStatus.DEACTIVATED) {
                return@runInTransaction
            }

            val now = clock.now()
            val deactivated =
                userRepository.deactivate(
                    userId = userId,
                    deactivatedAt = now,
                    updatedAt = now,
                )

            if (!deactivated) {
                throw UserNotFoundException()
            }

            refreshSessionRepository.revokeAllForUser(userId, now)
            userOutbox.appendUserDeactivated(userId, now)
        }
    }

    private fun issueAuthSession(
        userId: UserId,
        issuedAt: Instant,
    ): IssuedAuthSession {
        val principal = AuthenticatedPrincipal(subject = userId.value.toString())
        val accessToken = tokenService.issueAccessToken(principal = principal, issuedAt = issuedAt)
        val refreshToken = refreshSessionTokenService.issueRefreshToken(issuedAt = issuedAt)

        refreshSessionRepository.save(
            RefreshSession(
                id = RefreshSessionId(Uuid.random()),
                userId = userId,
                refreshTokenHash = refreshSessionTokenService.hash(refreshToken.value),
                expiresAt = refreshToken.expiresAt,
                revokedAt = null,
                createdAt = issuedAt,
            ),
        )

        return IssuedAuthSession(
            accessToken = accessToken,
            refreshToken = IssuedToken(value = refreshToken.value, expiresAt = refreshToken.expiresAt),
        )
    }
}

private fun IdentityUser.canAuthenticate(): Boolean = status == IdentityUserStatus.ACTIVE

data class RegisterIdentityUserCommand(
    val email: String,
    val username: String,
    val displayName: String,
    val password: String,
)

data class LoginIdentityUserCommand(
    val loginId: String,
    val password: String,
)

data class UpdateProfileCommand(
    val username: String?,
    val displayName: String?,
    val avatarMediaId: AvatarMediaId?,
)

data class AuthenticatedIdentityUser(
    val user: IdentityUser,
    val session: IssuedAuthSession,
)

sealed class IdentityApplicationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidIdentityInputException(
    message: String,
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

class InvalidCredentialsException(
    message: String = "Invalid credentials",
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

class InvalidRefreshTokenException(
    message: String = "Invalid refresh token",
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

class UserNotFoundException(
    message: String = "User not found",
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

private fun String.normalizeEmail(): String = trim().lowercase()

private fun String.normalizeUsername(): String = trim().lowercase()

private fun String.toUserIdOrNull(): UserId? = runCatching { UserId(Uuid.parse(trim())) }.getOrNull()

fun parseUserId(raw: String): UserId = UserId(parseGrpcUuid(raw, fieldName = "user_id"))

fun parseAvatarMediaId(raw: String): AvatarMediaId = AvatarMediaId(parseGrpcUuid(raw, fieldName = "avatar_media_id"))

fun parseAvatarMediaIdOrNull(raw: String?): AvatarMediaId? = parseGrpcUuidOrNull(raw, fieldName = "avatar_media_id")?.let(::AvatarMediaId)
