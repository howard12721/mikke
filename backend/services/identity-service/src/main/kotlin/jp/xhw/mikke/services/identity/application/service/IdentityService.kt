package jp.xhw.mikke.services.identity.application.service

import jp.xhw.mikke.platform.auth.IssuedClientSession
import jp.xhw.mikke.platform.auth.PasswordPolicy
import jp.xhw.mikke.platform.auth.session.SessionId
import jp.xhw.mikke.platform.auth.session.SessionRecordCodec
import jp.xhw.mikke.platform.auth.session.SessionValidation
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.pagination.PageSlice
import jp.xhw.mikke.platform.pagination.ValidatedPageRequest
import jp.xhw.mikke.services.identity.application.command.*
import jp.xhw.mikke.services.identity.application.exception.InvalidCredentialsException
import jp.xhw.mikke.services.identity.application.exception.InvalidIdentityInputException
import jp.xhw.mikke.services.identity.application.exception.InvalidSessionHashException
import jp.xhw.mikke.services.identity.application.exception.SessionVersionProjectionException
import jp.xhw.mikke.services.identity.application.exception.UserNotFoundException
import jp.xhw.mikke.services.identity.application.pagination.SearchUsersCursor
import jp.xhw.mikke.services.identity.application.port.ClientSessionStore
import jp.xhw.mikke.services.identity.application.port.IdentityUserOutbox
import jp.xhw.mikke.services.identity.application.port.IdentityUserRepository
import jp.xhw.mikke.services.identity.application.security.PasswordHasher
import jp.xhw.mikke.services.identity.model.*
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityService(
    private val userRepository: IdentityUserRepository,
    private val clientSessionStore: ClientSessionStore,
    private val userOutbox: IdentityUserOutbox,
    private val transactionRunner: TransactionRunner,
    private val passwordHasher: PasswordHasher,
    private val secureRandom: SecureRandom = SecureRandom(),
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
                    session = issueClientSession(user.id, now),
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

        return transactionRunner.runInTransaction {
            val currentUser =
                userRepository.findByIds(listOf(user.id)).firstOrNull()
                    ?: throw InvalidCredentialsException()

            if (!currentUser.canAuthenticate()) {
                throw InvalidCredentialsException()
            }

            AuthenticatedIdentityUser(
                user = currentUser,
                session = issueClientSession(currentUser.id, clock.now()),
            )
        }
    }

    fun touchSession(sessionHash: String) {
        requireValidSessionHash(sessionHash)
        val now = clock.now()
        val record =
            clientSessionStore.findSession(sessionHash)
                ?: return
        if (SessionValidation.validateRecord(record, projectedUserSessionVersion = record.userSessionVersion, now = now) != null) {
            return
        }
        if (!SessionValidation.shouldTouchSession(record, now)) {
            return
        }

        clientSessionStore.touchSession(
            sessionHash = sessionHash,
            record = SessionRecordCodec.touch(record, now),
        )
    }

    fun logoutSession(sessionHash: String) {
        requireValidSessionHash(sessionHash)
        clientSessionStore.deleteSession(sessionHash)
    }

    fun getMe(userId: UserId): IdentityUser =
        transactionRunner.runInTransaction {
            userRepository
                .findByIds(listOf(userId))
                .firstOrNull()
                ?.takeIf { it.canAuthenticate() }
                ?: throw UserNotFoundException()
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
        userId: UserId,
        command: UpdateProfileCommand,
    ): IdentityUser =
        transactionRunner.runInTransaction {
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

    fun deactivateAccount(userId: UserId) {
        transactionRunner.runInTransaction {
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

            revokeAllSessions(userId)
            userOutbox.appendUserDeactivated(userId, now)
        }
    }

    fun changePassword(
        userId: UserId,
        command: ChangePasswordCommand,
    ) {
        if (command.newPassword == command.currentPassword) {
            throw InvalidIdentityInputException("new_password must be different from current_password")
        }

        transactionRunner.runInTransaction {
            val current =
                userRepository.findByIds(listOf(userId)).firstOrNull()
                    ?: throw UserNotFoundException()

            if (!current.canAuthenticate()) {
                throw UserNotFoundException()
            }

            if (!passwordHasher.verify(command.currentPassword, current.passwordHash)) {
                throw InvalidCredentialsException()
            }

            PasswordPolicy.validate(command.newPassword)

            val newPasswordHash = passwordHasher.hash(command.newPassword)
            val now = clock.now()

            userRepository.changePassword(
                userId = userId,
                passwordHash = newPasswordHash,
                updatedAt = now,
            )

            revokeAllSessions(userId)
            userOutbox.appendPasswordChanged(userId, now)
        }
    }

    private fun issueClientSession(
        userId: UserId,
        issuedAt: Instant,
    ): IssuedClientSession {
        val sessionVersion = userRepository.getSessionVersion(userId)
        val sessionId = SessionId.generate(secureRandom)
        val sessionHash = SessionId.hash(sessionId)
        val record =
            SessionRecordCodec.createNew(
                userId = userId.value.toString(),
                userSessionVersion = sessionVersion,
                issuedAt = issuedAt,
            )

        clientSessionStore.saveSession(sessionHash, record)
        try {
            projectUserSessionVersion(userId, sessionVersion)
        } catch (e: SessionVersionProjectionException) {
            clientSessionStore.deleteSession(sessionHash)
            throw e
        }

        return IssuedClientSession(
            sessionId = sessionId,
            idleExpiresAt = record.idleExpiresAt,
            absoluteExpiresAt = record.absoluteExpiresAt,
        )
    }

    private fun revokeAllSessions(userId: UserId) {
        val nextVersion = userRepository.incrementSessionVersion(userId)
        projectUserSessionVersion(userId, nextVersion)
    }

    private fun projectUserSessionVersion(
        userId: UserId,
        version: Int,
    ) {
        try {
            clientSessionStore.saveUserSessionVersion(userId.value.toString(), version)
        } catch (e: Exception) {
            throw SessionVersionProjectionException(cause = e)
        }
    }

    private fun requireValidSessionHash(sessionHash: String) {
        if (!SessionId.isValidSessionHash(sessionHash)) {
            throw InvalidSessionHashException()
        }
    }
}

private fun IdentityUser.canAuthenticate(): Boolean = status == IdentityUserStatus.ACTIVE

private fun String.normalizeEmail(): String = trim().lowercase()

private fun String.normalizeUsername(): String = trim().lowercase()
