package jp.xhw.mikke.services.identity.application

import jp.xhw.mikke.events.user.UserEventTypes
import jp.xhw.mikke.platform.auth.jwt.JwtTokenService
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.services.identity.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityServiceTest {
    @Test
    fun `register rejects weak password`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)

        val exception =
            assertThrows(InvalidIdentityInputException::class.java) {
                service.register(
                    RegisterIdentityUserCommand(
                        email = "alice@example.com",
                        username = "alice",
                        displayName = "Alice",
                        password = "password",
                    ),
                )
            }

        assertEquals(
            "password must be at least 8 characters and include at least one letter and one digit",
            exception.message,
        )
        assertEquals(null, repository.savedUser)
        assertTrue(repository.outbox.entries.isEmpty())
    }

    @Test
    fun `register persists user and writes user created outbox`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)

        val result =
            service.register(
                RegisterIdentityUserCommand(
                    email = "alice@example.com",
                    username = "alice",
                    displayName = "Alice",
                    password = "password123",
                ),
            )

        assertNotNull(repository.savedUser)
        assertEquals("alice@example.com", result.user.email.value)
        assertEquals(IdentityUserStatus.ACTIVE, result.user.status)
        assertEquals(1, repository.outbox.entries.size)
        assertEquals(
            UserEventTypes.CREATED,
            repository.outbox.entries
                .single()
                .eventType,
        )
    }

    @Test
    fun `register duplicate email throws duplicate exception`() {
        val repository = RecordingIdentityUserRepository()
        repository.duplicateOnSave = true
        val service = createService(repository)

        assertThrows(DuplicateIdentityUserException::class.java) {
            service.register(
                RegisterIdentityUserCommand(
                    email = "alice@example.com",
                    username = "alice",
                    displayName = "Alice",
                    password = "password123",
                ),
            )
        }
    }

    @Test
    fun `refresh rotates refresh session and invalidates previous token`() {
        val repository = RecordingIdentityUserRepository()
        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-04-23T00:00:00Z")
            }
        val service = createService(repository, clock = fixedClock)

        val registered =
            service.register(
                RegisterIdentityUserCommand(
                    email = "alice@example.com",
                    username = "alice",
                    displayName = "Alice",
                    password = "password123",
                ),
            )

        val refreshed = service.refreshSession(registered.session.refreshToken.value)

        assertNotEquals(registered.session.refreshToken.value, refreshed.refreshToken.value)
        assertThrows(InvalidRefreshTokenException::class.java) {
            service.refreshSession(registered.session.refreshToken.value)
        }
        assertEquals(2, repository.refreshSessions.sessions.size)
        assertEquals(1, repository.refreshSessions.sessions.count { it.revokedAt != null })
    }

    @Test
    fun `logout revokes refresh session`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)

        val registered =
            service.register(
                RegisterIdentityUserCommand(
                    email = "alice@example.com",
                    username = "alice",
                    displayName = "Alice",
                    password = "password123",
                ),
            )

        service.logout(registered.session.refreshToken.value)

        assertThrows(InvalidRefreshTokenException::class.java) {
            service.refreshSession(registered.session.refreshToken.value)
        }
    }

    @Test
    fun `getUser returns public profile without email`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)
        val registered = registerAlice(service)

        val user = service.getUser(registered.user.id)

        assertEquals(registered.user.id, user.id)
        assertEquals("alice", user.username.value)
    }

    @Test
    fun `getUser hides deactivated users`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)
        val registered = registerAlice(service)

        repository.savedUser = registered.user.copy(status = IdentityUserStatus.DEACTIVATED)

        assertThrows(UserNotFoundException::class.java) {
            service.getUser(registered.user.id)
        }
    }

    @Test
    fun `batchGetUsers returns only existing active users`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)
        val alice = registerAlice(service)
        val missingId = UserId(Uuid.random())

        val users =
            service.batchGetUsers(
                listOf(alice.user.id, missingId),
            )

        assertEquals(1, users.size)
        assertEquals(alice.user.id, users.single().id)
    }

    @Test
    fun `searchUsers matches username prefix`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)
        repository.seedSearchUsers(
            listOf(
                user(username = "alice", displayName = "Alice"),
                user(username = "alex", displayName = "Alex"),
                user(username = "bob", displayName = "Bob"),
            ),
        )

        val result =
            service.searchUsers(
                query = "al",
                page = PageRequestInput(pageSize = 10).validate(cursorDecoder = SearchUsersCursor.Companion::decode),
            )

        assertEquals(listOf("alex", "alice"), result.items.map { it.username.value })
    }

    @Test
    fun `updateProfile normalizes username and writes profile updated outbox`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)
        val registered = registerAlice(service)

        val updated =
            service.updateProfile(
                subject =
                    registered.user.id.value
                        .toString(),
                command =
                    UpdateProfileCommand(
                        username = "Alice_Official",
                        displayName = null,
                        avatarMediaId = null,
                    ),
            )

        assertEquals("Alice_Official", updated.username.value)
        assertEquals(2, repository.outbox.entries.size)
        assertEquals(
            UserEventTypes.PROFILE_UPDATED,
            repository.outbox.entries
                .last()
                .eventType,
        )
    }

    @Test
    fun `updateProfile duplicate username throws duplicate exception`() {
        val repository = RecordingIdentityUserRepository()
        repository.duplicateOnUpdate = true
        val service = createService(repository)
        val registered = registerAlice(service)

        assertThrows(DuplicateIdentityUserException::class.java) {
            service.updateProfile(
                subject =
                    registered.user.id.value
                        .toString(),
                command = UpdateProfileCommand(username = "bob", displayName = null, avatarMediaId = null),
            )
        }
    }

    @Test
    fun `deactivate blocks login and public profile lookup`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)
        val registered = registerAlice(service)

        service.deactivateAccount(
            registered.user.id.value
                .toString(),
        )

        assertThrows(InvalidCredentialsException::class.java) {
            service.login(
                LoginIdentityUserCommand(
                    loginId = "alice@example.com",
                    password = "password123",
                ),
            )
        }
        assertThrows(UserNotFoundException::class.java) {
            service.getUser(registered.user.id)
        }
        assertEquals(
            UserEventTypes.DEACTIVATED,
            repository.outbox.entries
                .last()
                .eventType,
        )
    }

    @Test
    fun `user created outbox payload contains expected fields`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository)

        service.register(
            RegisterIdentityUserCommand(
                email = "alice@example.com",
                username = "alice",
                displayName = "Alice",
                password = "password123",
            ),
        )

        val payload =
            Json
                .parseToJsonElement(
                    repository.outbox.entries
                        .single()
                        .payloadJson,
                ).jsonObject
        assertEquals("alice", payload.getValue("username").jsonPrimitive.content)
        assertEquals("Alice", payload.getValue("display_name").jsonPrimitive.content)
        assertTrue(payload.containsKey("user_id"))
        assertTrue(payload.containsKey("created_at"))
    }

    private fun createService(
        repository: RecordingIdentityUserRepository,
        clock: Clock = Clock.System,
    ): IdentityService =
        IdentityService(
            userRepository = repository,
            refreshSessionRepository = repository.refreshSessions,
            userOutbox = repository.outbox,
            transactionRunner = ImmediateTransactionRunner,
            passwordHasher = PasswordHasher(),
            tokenService = JwtTokenService(secret = "test-secret", clock = clock),
            refreshSessionTokenService = RefreshSessionTokenService(),
            clock = clock,
        )

    private fun registerAlice(service: IdentityService): AuthenticatedIdentityUser =
        service.register(
            RegisterIdentityUserCommand(
                email = "alice@example.com",
                username = "alice",
                displayName = "Alice",
                password = "password123",
            ),
        )

    private fun user(
        username: String,
        displayName: String,
    ): IdentityUser {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        return IdentityUser(
            id = UserId(Uuid.random()),
            email = Email("$username@example.com"),
            username = Username(username),
            displayName = DisplayName(displayName),
            passwordHash = PasswordHash(iterations = 1, hash = "hash", salt = "salt"),
            avatarMediaId = null,
            status = IdentityUserStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            deactivatedAt = null,
        )
    }
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private class RecordingIdentityUserRepository :
    IdentityUserRepository,
    IdentityUserOutbox {
    var savedUser: IdentityUser? = null
    var duplicateOnSave: Boolean = false
    var duplicateOnUpdate: Boolean = false
    val refreshSessions = RecordingRefreshSessionRepository()
    val outbox = RecordingIdentityUserOutbox()
    private var searchUsers: List<IdentityUser> = emptyList()

    override fun saveUser(user: IdentityUser) {
        if (duplicateOnSave) {
            throw DuplicateIdentityUserException()
        }
        savedUser = user
    }

    override fun findByLogin(login: String): IdentityUser? = savedUser

    override fun findByEmails(emails: List<Email>): List<IdentityUser> = emptyList()

    override fun findByIds(ids: List<UserId>): List<IdentityUser> {
        val user = savedUser ?: return emptyList()
        return ids.filter { it == user.id }.map { user }
    }

    override fun searchByUsernamePrefix(
        normalizedPrefix: String,
        limit: Int,
        cursor: SearchUsersCursor?,
    ): List<IdentityUser> {
        val source = if (searchUsers.isNotEmpty()) searchUsers else savedUser?.let(::listOf).orEmpty()
        return source
            .filter { it.isPubliclyVisible() }
            .filter {
                it.username.value
                    .normalizeUsername()
                    .startsWith(normalizedPrefix)
            }.sortedWith(compareBy({ it.username.value.normalizeUsername() }, { it.id.value }))
            .dropWhile { cursor != null && !isAfterCursor(it, cursor) }
            .take(limit)
    }

    override fun updateProfile(user: IdentityUser) {
        if (duplicateOnUpdate) {
            throw DuplicateIdentityUserException()
        }
        savedUser = user
    }

    override fun deactivate(
        userId: UserId,
        deactivatedAt: Instant,
        updatedAt: Instant,
    ): Boolean {
        val current = savedUser ?: return false
        if (current.id != userId) {
            return false
        }
        savedUser =
            current.copy(
                status = IdentityUserStatus.DEACTIVATED,
                deactivatedAt = deactivatedAt,
                updatedAt = updatedAt,
            )
        return true
    }

    override fun appendUserCreated(user: IdentityUser) = outbox.appendUserCreated(user)

    override fun appendProfileUpdated(user: IdentityUser) = outbox.appendProfileUpdated(user)

    override fun appendUserDeactivated(
        userId: UserId,
        deactivatedAt: Instant,
    ) = outbox.appendUserDeactivated(userId, deactivatedAt)

    fun seedSearchUsers(users: List<IdentityUser>) {
        searchUsers = users
    }

    private fun isAfterCursor(
        user: IdentityUser,
        cursor: SearchUsersCursor,
    ): Boolean {
        val normalized = user.username.value.normalizeUsername()
        return normalized > cursor.normalizedUsername ||
            (normalized == cursor.normalizedUsername && user.id.value > cursor.id)
    }

    private fun String.normalizeUsername(): String = trim().lowercase()
}

private class RecordingIdentityUserOutbox : IdentityUserOutbox {
    val entries = mutableListOf<OutboxEntry>()

    override fun appendUserCreated(user: IdentityUser) {
        entries +=
            OutboxEntry(
                id = Uuid.random(),
                eventType = UserEventTypes.CREATED,
                aggregateType = "user",
                aggregateId = user.id.value,
                payloadJson =
                    """
                    {"user_id":"${user.id.value}","username":"${user.username.value}","display_name":"${user.displayName.value}","created_at":"${user.createdAt}"}
                    """.trimIndent(),
                createdAt = user.createdAt,
            )
    }

    override fun appendProfileUpdated(user: IdentityUser) {
        entries +=
            OutboxEntry(
                id = Uuid.random(),
                eventType = UserEventTypes.PROFILE_UPDATED,
                aggregateType = "user",
                aggregateId = user.id.value,
                payloadJson = """{"user_id":"${user.id.value}","username":"${user.username.value}"}""",
                createdAt = user.updatedAt,
            )
    }

    override fun appendUserDeactivated(
        userId: UserId,
        deactivatedAt: Instant,
    ) {
        entries +=
            OutboxEntry(
                id = Uuid.random(),
                eventType = UserEventTypes.DEACTIVATED,
                aggregateType = "user",
                aggregateId = userId.value,
                payloadJson = """{"user_id":"${userId.value}"}""",
                createdAt = deactivatedAt,
            )
    }
}

private class RecordingRefreshSessionRepository : RefreshSessionRepository {
    val sessions = mutableListOf<RefreshSession>()

    override fun save(session: RefreshSession) {
        sessions += session
    }

    override fun findByRefreshTokenHash(refreshTokenHash: String): RefreshSession? =
        sessions.lastOrNull { it.refreshTokenHash == refreshTokenHash }

    override fun revoke(
        sessionId: RefreshSessionId,
        revokedAt: Instant,
    ): Boolean {
        val index =
            sessions.indexOfFirst {
                it.id == sessionId && it.revokedAt == null
            }
        if (index < 0) {
            return false
        }

        sessions[index] = sessions[index].copy(revokedAt = revokedAt)
        return true
    }

    override fun revokeByRefreshTokenHash(
        refreshTokenHash: String,
        revokedAt: Instant,
    ): Boolean {
        val index =
            sessions.indexOfFirst {
                it.refreshTokenHash == refreshTokenHash && it.revokedAt == null
            }
        if (index < 0) {
            return false
        }

        sessions[index] = sessions[index].copy(revokedAt = revokedAt)
        return true
    }

    override fun revokeAllForUser(
        userId: UserId,
        revokedAt: Instant,
    ) {
        sessions.indices.forEach { index ->
            val session = sessions[index]
            if (session.userId == userId && session.revokedAt == null) {
                sessions[index] = session.copy(revokedAt = revokedAt)
            }
        }
    }
}
