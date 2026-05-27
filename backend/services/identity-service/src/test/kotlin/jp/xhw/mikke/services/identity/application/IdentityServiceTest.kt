package jp.xhw.mikke.services.identity.application

import jp.xhw.mikke.events.user.UserEventTypes
import jp.xhw.mikke.platform.auth.session.SessionId
import jp.xhw.mikke.platform.auth.session.SessionLifetime
import jp.xhw.mikke.platform.auth.session.SessionRecord
import jp.xhw.mikke.platform.auth.session.SessionRecordCodec
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.services.identity.application.command.AuthenticatedIdentityUser
import jp.xhw.mikke.services.identity.application.command.LoginIdentityUserCommand
import jp.xhw.mikke.services.identity.application.command.RegisterIdentityUserCommand
import jp.xhw.mikke.services.identity.application.command.UpdateProfileCommand
import jp.xhw.mikke.services.identity.application.exception.*
import jp.xhw.mikke.services.identity.application.pagination.SearchUsersCursor
import jp.xhw.mikke.services.identity.application.port.ClientSessionStore
import jp.xhw.mikke.services.identity.application.port.IdentityUserOutbox
import jp.xhw.mikke.services.identity.application.port.IdentityUserRepository
import jp.xhw.mikke.services.identity.application.security.PasswordHasher
import jp.xhw.mikke.services.identity.application.service.IdentityService
import jp.xhw.mikke.services.identity.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityServiceTest {
    @Test
    fun `register rejects weak password`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository, RecordingClientSessionStore())

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
        val service = createService(repository, RecordingClientSessionStore())

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
        val service = createService(repository, RecordingClientSessionStore())

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
    fun `register issues opaque session and writes redis state`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-04-23T00:00:00Z")
            }
        val service = createService(repository, sessionStore, clock = fixedClock)

        val result =
            service.register(
                RegisterIdentityUserCommand(
                    email = "alice@example.com",
                    username = "alice",
                    displayName = "Alice",
                    password = "password123",
                ),
            )

        assertTrue(SessionId.isValidSessionId(result.session.sessionId))
        assertEquals(fixedClock.now() + SessionLifetime.idleLifetime, result.session.idleExpiresAt)
        assertEquals(fixedClock.now() + SessionLifetime.absoluteLifetime, result.session.absoluteExpiresAt)

        val sessionHash = SessionId.hash(result.session.sessionId)
        val stored = sessionStore.sessions.getValue(sessionHash)
        assertEquals(
            result.user.id.value
                .toString(),
            stored.userId,
        )
        assertEquals(0, stored.userSessionVersion)
        assertEquals(
            0,
            sessionStore.userSessionVersions.getValue(
                result.user.id.value
                    .toString(),
            ),
        )
    }

    @Test
    fun `logout revokes current session by hash`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)

        val sessionHash = SessionId.hash(registered.session.sessionId)
        service.logoutSession(sessionHash)

        assertFalse(sessionStore.sessions.containsKey(sessionHash))
    }

    @Test
    fun `touch extends idle expiry after threshold`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val issuedAt = Instant.parse("2026-04-23T00:00:00Z")
        val fixedClock =
            object : Clock {
                override fun now(): Instant = issuedAt + 25.hours
            }
        val service = createService(repository, sessionStore, clock = fixedClock)
        val registered = registerAlice(service, sessionStore, issuedAtOverride = issuedAt)
        val sessionHash = SessionId.hash(registered.session.sessionId)

        service.touchSession(sessionHash)

        val touched = sessionStore.sessions.getValue(sessionHash)
        assertEquals(issuedAt + 25.hours, touched.lastTouchedAt)
        assertEquals(issuedAt + 25.hours + SessionLifetime.idleLifetime, touched.idleExpiresAt)
    }

    @Test
    fun `change password increments user session version projection`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)

        service.changePassword(
            userId = registered.user.id,
            command =
                jp.xhw.mikke.services.identity.application.command.ChangePasswordCommand(
                    currentPassword = "password123",
                    newPassword = "password456",
                ),
        )

        assertEquals(1, repository.sessionVersion)
        assertEquals(
            1,
            sessionStore.userSessionVersions.getValue(
                registered.user.id.value
                    .toString(),
            ),
        )
    }

    @Test
    fun `login session version projection does not regress`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)
        val userId =
            registered.user.id.value
                .toString()
        sessionStore.userSessionVersions[userId] = 1

        service.login(
            LoginIdentityUserCommand(
                loginId = "alice",
                password = "password123",
            ),
        )

        assertEquals(1, sessionStore.userSessionVersions.getValue(userId))
    }

    @Test
    fun `session version projection failure rolls back issued session`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore =
            RecordingClientSessionStore().apply {
                failVersionProjection = true
            }
        val service = createService(repository, sessionStore)

        assertThrows(SessionVersionProjectionException::class.java) {
            service.register(
                RegisterIdentityUserCommand(
                    email = "alice@example.com",
                    username = "alice",
                    displayName = "Alice",
                    password = "password123",
                ),
            )
        }

        assertTrue(sessionStore.sessions.isEmpty())
    }

    @Test
    fun `getUser returns public profile without email`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)

        val user = service.getUser(registered.user.id)

        assertEquals(registered.user.id, user.id)
        assertEquals("alice", user.username.value)
    }

    @Test
    fun `getUser hides deactivated users`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)

        repository.savedUser = registered.user.copy(status = IdentityUserStatus.DEACTIVATED)

        assertThrows(UserNotFoundException::class.java) {
            service.getUser(registered.user.id)
        }
    }

    @Test
    fun `batchGetUsers returns only existing active users`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val alice = registerAlice(service, sessionStore)
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
        val service = createService(repository, RecordingClientSessionStore())
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
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)

        val updated =
            service.updateProfile(
                userId = registered.user.id,
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
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)

        assertThrows(DuplicateIdentityUserException::class.java) {
            service.updateProfile(
                userId = registered.user.id,
                command = UpdateProfileCommand(username = "bob", displayName = null, avatarMediaId = null),
            )
        }
    }

    @Test
    fun `deactivate blocks login and public profile lookup`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)

        service.deactivateAccount(registered.user.id)

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
    fun `login rejects user deactivated after password verification`() {
        val repository = RecordingIdentityUserRepository()
        val sessionStore = RecordingClientSessionStore()
        val service = createService(repository, sessionStore)
        val registered = registerAlice(service, sessionStore)

        repository.loginUser = registered.user
        repository.savedUser = registered.user.copy(status = IdentityUserStatus.DEACTIVATED)

        assertThrows(InvalidCredentialsException::class.java) {
            service.login(
                LoginIdentityUserCommand(
                    loginId = "alice@example.com",
                    password = "password123",
                ),
            )
        }
        assertEquals(1, sessionStore.sessions.size)
    }

    @Test
    fun `user created outbox payload contains expected fields`() {
        val repository = RecordingIdentityUserRepository()
        val service = createService(repository, RecordingClientSessionStore())

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
        sessionStore: RecordingClientSessionStore,
        clock: Clock = Clock.System,
    ): IdentityService =
        IdentityService(
            userRepository = repository,
            clientSessionStore = sessionStore,
            userOutbox = repository.outbox,
            transactionRunner = ImmediateTransactionRunner,
            passwordHasher = PasswordHasher(),
            clock = clock,
        )

    private fun registerAlice(
        service: IdentityService,
        sessionStore: RecordingClientSessionStore,
        issuedAtOverride: Instant? = null,
    ): AuthenticatedIdentityUser {
        val result =
            service.register(
                RegisterIdentityUserCommand(
                    email = "alice@example.com",
                    username = "alice",
                    displayName = "Alice",
                    password = "password123",
                ),
            )
        if (issuedAtOverride != null) {
            val sessionHash = SessionId.hash(result.session.sessionId)
            val record =
                SessionRecordCodec.createNew(
                    userId =
                        result.user.id.value
                            .toString(),
                    userSessionVersion = 0,
                    issuedAt = issuedAtOverride,
                )
            sessionStore.sessions[sessionHash] = record
        }
        return result
    }

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
    var loginUser: IdentityUser? = null
    var duplicateOnSave: Boolean = false
    var duplicateOnUpdate: Boolean = false
    var sessionVersion: Int = 0
    val outbox = RecordingIdentityUserOutbox()
    private var searchUsers: List<IdentityUser> = emptyList()

    override fun saveUser(user: IdentityUser) {
        if (duplicateOnSave) {
            throw DuplicateIdentityUserException()
        }
        savedUser = user
    }

    override fun findByLogin(login: String): IdentityUser? = loginUser ?: savedUser

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

    override fun changePassword(
        userId: UserId,
        passwordHash: PasswordHash,
        updatedAt: Instant,
    ): Boolean {
        val current = savedUser ?: return false
        if (current.id != userId) {
            return false
        }
        savedUser = current.copy(passwordHash = passwordHash, updatedAt = updatedAt)
        return true
    }

    override fun getSessionVersion(userId: UserId): Int {
        val current = savedUser ?: throw UserNotFoundException()
        if (current.id != userId) {
            throw UserNotFoundException()
        }
        return sessionVersion
    }

    override fun incrementSessionVersion(userId: UserId): Int {
        val current = savedUser ?: throw UserNotFoundException()
        if (current.id != userId) {
            throw UserNotFoundException()
        }
        sessionVersion += 1
        return sessionVersion
    }

    override fun appendUserCreated(user: IdentityUser) = outbox.appendUserCreated(user)

    override fun appendProfileUpdated(user: IdentityUser) = outbox.appendProfileUpdated(user)

    override fun appendUserDeactivated(
        userId: UserId,
        deactivatedAt: Instant,
    ) = outbox.appendUserDeactivated(userId, deactivatedAt)

    override fun appendPasswordChanged(
        userId: UserId,
        updatedAt: Instant,
    ) = outbox.appendPasswordChanged(userId, updatedAt)

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

    override fun appendPasswordChanged(
        userId: UserId,
        updatedAt: Instant,
    ) {
        entries +=
            OutboxEntry(
                id = Uuid.random(),
                eventType = UserEventTypes.PASSWORD_CHANGED,
                aggregateType = "user",
                aggregateId = userId.value,
                payloadJson = """{"user_id":"${userId.value}"}""",
                createdAt = updatedAt,
            )
    }
}

private class RecordingClientSessionStore : ClientSessionStore {
    val sessions = mutableMapOf<String, SessionRecord>()
    val userSessionVersions = mutableMapOf<String, Int>()
    var failVersionProjection: Boolean = false

    override fun saveSession(
        sessionHash: String,
        record: SessionRecord,
    ) {
        sessions[sessionHash] = record
    }

    override fun findSession(sessionHash: String): SessionRecord? = sessions[sessionHash]

    override fun touchSession(
        sessionHash: String,
        record: SessionRecord,
    ) {
        saveSession(sessionHash, record)
    }

    override fun deleteSession(sessionHash: String): Boolean = sessions.remove(sessionHash) != null

    override fun saveUserSessionVersion(
        userId: String,
        version: Int,
    ) {
        if (failVersionProjection) {
            throw IllegalStateException("redis unavailable")
        }
        userSessionVersions[userId] = maxOf(userSessionVersions[userId] ?: version, version)
    }
}
