package jp.xhw.mikke.services.identity

import io.grpc.Status
import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.common.v1.PageRequest
import jp.xhw.mikke.identity.v1.*
import jp.xhw.mikke.platform.auth.session.SessionId
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.grpc.toGrpcStatusRuntimeException
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.services.identity.application.exception.DuplicateIdentityUserException
import jp.xhw.mikke.services.identity.application.exception.IdentityApplicationException
import jp.xhw.mikke.services.identity.application.pagination.SearchUsersCursor
import jp.xhw.mikke.services.identity.application.port.ClientSessionStore
import jp.xhw.mikke.services.identity.application.port.IdentityUserOutbox
import jp.xhw.mikke.services.identity.application.port.IdentityUserRepository
import jp.xhw.mikke.services.identity.application.security.PasswordHasher
import jp.xhw.mikke.services.identity.application.service.IdentityService
import jp.xhw.mikke.services.identity.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityServiceRpcTest {
    @Test
    fun `registerUser returns user and opaque session`() =
        runBlocking {
            val repository = RecordingIdentityUserRepository()
            val rpc = createRpc(repository)

            val response =
                rpc.registerUser(
                    RegisterUserRequest
                        .newBuilder()
                        .setEmail(" alice@example.com ")
                        .setUsername(" alice ")
                        .setDisplayName(" Alice ")
                        .setPassword("password123")
                        .build(),
                )

            assertEquals("alice@example.com", response.user.email)
            assertEquals("alice", response.user.username)
            assertTrue(SessionId.isValidSessionId(response.session.sessionId))
            assertTrue(response.session.hasIdleExpiresAt())
            assertTrue(response.session.hasAbsoluteExpiresAt())
            assertEquals(1, repository.outbox.entries.size)
        }

    @Test
    fun `registerUser maps missing field to invalid argument`() =
        runBlocking {
            val rpc = createRpc()

            val error =
                assertStatus(Status.Code.INVALID_ARGUMENT) {
                    rpc.registerUser(
                        RegisterUserRequest
                            .newBuilder()
                            .setEmail(" ")
                            .setUsername("alice")
                            .setDisplayName("Alice")
                            .setPassword("password123")
                            .build(),
                    )
                }

            assertEquals("email is required", error.description)
        }

    @Test
    fun `registerUser maps weak password to invalid argument`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.INVALID_ARGUMENT) {
                rpc.registerUser(
                    RegisterUserRequest
                        .newBuilder()
                        .setEmail("alice@example.com")
                        .setUsername("alice")
                        .setDisplayName("Alice")
                        .setPassword("password")
                        .build(),
                )
            }
        }

    @Test
    fun `registerUser maps duplicate user to already exists`(): Unit =
        runBlocking {
            val repository = RecordingIdentityUserRepository(duplicateOnSave = true)
            val rpc = createRpc(repository)

            assertStatus(Status.Code.ALREADY_EXISTS) {
                rpc.registerUser(
                    RegisterUserRequest
                        .newBuilder()
                        .setEmail("alice@example.com")
                        .setUsername("alice")
                        .setDisplayName("Alice")
                        .setPassword("password123")
                        .build(),
                )
            }
        }

    @Test
    fun `loginUser maps bad credentials to unauthenticated`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.UNAUTHENTICATED) {
                rpc.loginUser(
                    LoginUserRequest
                        .newBuilder()
                        .setLoginId("alice@example.com")
                        .setPassword("password123")
                        .build(),
                )
            }
        }

    @Test
    fun `getMe requires actor user id`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.INVALID_ARGUMENT) {
                rpc.getMe(GetMeRequest.getDefaultInstance())
            }
        }

    @Test
    fun `getMe returns authenticated user`() =
        runBlocking {
            val repository = RecordingIdentityUserRepository()
            val rpc = createRpc(repository)
            val registered =
                rpc.registerUser(
                    RegisterUserRequest
                        .newBuilder()
                        .setEmail("alice@example.com")
                        .setUsername("alice")
                        .setDisplayName("Alice")
                        .setPassword("password123")
                        .build(),
                )

            val response =
                rpc.getMe(
                    GetMeRequest
                        .newBuilder()
                        .setActor(
                            ActorContext
                                .newBuilder()
                                .setUserId(registered.user.id)
                                .build(),
                        ).build(),
                )

            assertEquals(registered.user.id, response.user.id)
        }

    @Test
    fun `getUser maps invalid user id to invalid argument`() =
        runBlocking {
            val rpc = createRpc()

            val error =
                assertStatus(Status.Code.INVALID_ARGUMENT) {
                    rpc.getUser(GetUserRequest.newBuilder().setUserId("not-a-uuid").build())
                }

            assertEquals("user_id must be a valid UUID", error.description)
        }

    @Test
    fun `getUser maps missing user to not found`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.NOT_FOUND) {
                rpc.getUser(GetUserRequest.newBuilder().setUserId(Uuid.random().toString()).build())
            }
        }

    @Test
    fun `searchUsers maps invalid page to invalid argument`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.INVALID_ARGUMENT) {
                rpc.searchUsers(
                    SearchUsersRequest
                        .newBuilder()
                        .setQuery("alice")
                        .setPage(PageRequest.newBuilder().setPageSize(-1))
                        .build(),
                )
            }
        }

    @Test
    fun `searchUsers maps blank query to invalid argument`() =
        runBlocking {
            val rpc = createRpc()

            val error =
                assertStatus(Status.Code.INVALID_ARGUMENT) {
                    rpc.searchUsers(
                        SearchUsersRequest
                            .newBuilder()
                            .setQuery(" ")
                            .setPage(PageRequest.getDefaultInstance())
                            .build(),
                    )
                }

            assertEquals("query is required", error.description)
        }

    @Test
    fun `updateProfile requires actor`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.INVALID_ARGUMENT) {
                rpc.updateProfile(
                    UpdateProfileRequest.newBuilder().setUsername("alice2").build(),
                )
            }
        }

    @Test
    fun `updateProfile maps invalid avatar id to invalid argument`() =
        runBlocking {
            val repository = RecordingIdentityUserRepository()
            val rpc = createRpc(repository)
            val registered =
                rpc.registerUser(
                    RegisterUserRequest
                        .newBuilder()
                        .setEmail("alice@example.com")
                        .setUsername("alice")
                        .setDisplayName("Alice")
                        .setPassword("password123")
                        .build(),
                )

            val error =
                assertStatus(Status.Code.INVALID_ARGUMENT) {
                    rpc.updateProfile(
                        UpdateProfileRequest
                            .newBuilder()
                            .setActor(
                                ActorContext
                                    .newBuilder()
                                    .setUserId(registered.user.id)
                                    .build(),
                            ).setAvatarMediaId("bad-avatar")
                            .build(),
                    )
                }

            assertEquals("avatar_media_id must be a valid UUID", error.description)
        }

    @Test
    fun `unexpected exception is mapped to internal`() =
        runBlocking {
            val rpc = createRpc(ThrowingIdentityUserRepository())

            val error =
                assertStatus(Status.Code.INTERNAL) {
                    rpc.registerUser(
                        RegisterUserRequest
                            .newBuilder()
                            .setEmail("alice@example.com")
                            .setUsername("alice")
                            .setDisplayName("Alice")
                            .setPassword("password123")
                            .build(),
                    )
                }

            assertEquals("Internal identity service error", error.description)
        }

    private fun createRpc(repository: RecordingIdentityUserRepository = RecordingIdentityUserRepository()): IdentityServiceRpc =
        IdentityServiceRpc(
            IdentityService(
                userRepository = repository,
                clientSessionStore = repository.sessionStore,
                userOutbox = repository.outbox,
                transactionRunner = ImmediateTransactionRunner,
                passwordHasher = PasswordHasher(),
                clock = fixedClock,
            ),
        )

    private companion object {
        val fixedClock: Clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-05-18T00:00:00Z")
            }
    }
}

private suspend inline fun assertStatus(
    expectedCode: Status.Code,
    crossinline block: suspend () -> Unit,
): Status {
    val thrown =
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        } ?: throw AssertionError("Expected gRPC status $expectedCode")
    val status =
        Status.fromThrowable(
            thrown.toGrpcStatusRuntimeException(
                logger = Logger.getLogger(IdentityServiceRpcTest::class.java.name),
                serviceName = "identity-service",
                internalErrorDescription = "Internal identity service error",
                domainExceptionMapper = { throwable ->
                    (throwable as? IdentityApplicationException)?.toGrpcStatus()
                },
            ),
        )
    assertEquals(expectedCode, status.code)
    return status
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private open class RecordingIdentityUserRepository(
    private val duplicateOnSave: Boolean = false,
) : IdentityUserRepository,
    IdentityUserOutbox {
    var savedUser: IdentityUser? = null
    val sessionStore = RecordingClientSessionStore()
    val outbox = RecordingIdentityUserOutbox()
    var sessionVersion: Int = 0

    override fun saveUser(user: IdentityUser) {
        if (duplicateOnSave) {
            throw DuplicateIdentityUserException()
        }
        savedUser = user
    }

    override fun findByLogin(login: String): IdentityUser? = savedUser

    override fun findByEmails(emails: List<Email>): List<IdentityUser> =
        savedUser
            ?.takeIf { user -> emails.any { it == user.email } }
            ?.let(::listOf)
            .orEmpty()

    override fun findByIds(ids: List<UserId>): List<IdentityUser> =
        savedUser
            ?.takeIf { user -> ids.any { it == user.id } }
            ?.let(::listOf)
            .orEmpty()

    override fun searchByUsernamePrefix(
        normalizedPrefix: String,
        limit: Int,
        cursor: SearchUsersCursor?,
    ): List<IdentityUser> =
        savedUser
            ?.takeIf {
                it.username.value
                    .lowercase()
                    .startsWith(normalizedPrefix) &&
                    it.status == IdentityUserStatus.ACTIVE
            }?.let(::listOf)
            .orEmpty()
            .take(limit)

    override fun updateProfile(user: IdentityUser) {
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
        val current =
            savedUser ?: throw jp.xhw.mikke.services.identity.application.exception
                .UserNotFoundException()
        if (current.id != userId) {
            throw jp.xhw.mikke.services.identity.application.exception
                .UserNotFoundException()
        }
        return sessionVersion
    }

    override fun incrementSessionVersion(userId: UserId): Int {
        val current =
            savedUser ?: throw jp.xhw.mikke.services.identity.application.exception
                .UserNotFoundException()
        if (current.id != userId) {
            throw jp.xhw.mikke.services.identity.application.exception
                .UserNotFoundException()
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
}

private class ThrowingIdentityUserRepository : RecordingIdentityUserRepository() {
    override fun saveUser(user: IdentityUser): Unit = throw IllegalStateException("database failed")
}

private class RecordingIdentityUserOutbox : IdentityUserOutbox {
    val entries = mutableListOf<OutboxEntry>()

    override fun appendUserCreated(user: IdentityUser) {
        entries += outboxEntry(user.id.value, user.createdAt)
    }

    override fun appendProfileUpdated(user: IdentityUser) {
        entries += outboxEntry(user.id.value, user.updatedAt)
    }

    override fun appendUserDeactivated(
        userId: UserId,
        deactivatedAt: Instant,
    ) {
        entries += outboxEntry(userId.value, deactivatedAt)
    }

    override fun appendPasswordChanged(
        userId: UserId,
        updatedAt: Instant,
    ) {
        entries += outboxEntry(userId.value, updatedAt)
    }

    private fun outboxEntry(
        aggregateId: Uuid,
        createdAt: Instant,
    ): OutboxEntry =
        OutboxEntry(
            id = Uuid.random(),
            eventType = "test",
            aggregateType = "user",
            aggregateId = aggregateId,
            payloadJson = "{}",
            createdAt = createdAt,
        )
}

private class RecordingClientSessionStore : ClientSessionStore {
    val sessions = mutableMapOf<String, jp.xhw.mikke.platform.auth.session.SessionRecord>()
    val userSessionVersions = mutableMapOf<String, Int>()

    override fun saveSession(
        sessionHash: String,
        record: jp.xhw.mikke.platform.auth.session.SessionRecord,
    ) {
        sessions[sessionHash] = record
    }

    override fun findSession(sessionHash: String): jp.xhw.mikke.platform.auth.session.SessionRecord? = sessions[sessionHash]

    override fun touchSession(
        sessionHash: String,
        record: jp.xhw.mikke.platform.auth.session.SessionRecord,
    ) {
        saveSession(sessionHash, record)
    }

    override fun deleteSession(sessionHash: String): Boolean = sessions.remove(sessionHash) != null

    override fun saveUserSessionVersion(
        userId: String,
        version: Int,
    ) {
        userSessionVersions[userId] = maxOf(userSessionVersions[userId] ?: version, version)
    }
}
