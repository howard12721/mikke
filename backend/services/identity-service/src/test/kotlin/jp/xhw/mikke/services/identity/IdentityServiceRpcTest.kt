package jp.xhw.mikke.services.identity

import io.grpc.Status
import jp.xhw.mikke.common.v1.PageRequest
import jp.xhw.mikke.identity.v1.*
import jp.xhw.mikke.platform.auth.AuthenticatedPrincipal
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthContext
import jp.xhw.mikke.platform.auth.jwt.JwtTokenService
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.grpc.toGrpcStatusRuntimeException
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.services.identity.application.exception.DuplicateIdentityUserException
import jp.xhw.mikke.services.identity.application.exception.IdentityApplicationException
import jp.xhw.mikke.services.identity.application.pagination.SearchUsersCursor
import jp.xhw.mikke.services.identity.application.port.IdentityUserOutbox
import jp.xhw.mikke.services.identity.application.port.IdentityUserRepository
import jp.xhw.mikke.services.identity.application.port.RefreshSessionRepository
import jp.xhw.mikke.services.identity.application.security.PasswordHasher
import jp.xhw.mikke.services.identity.application.security.RefreshSessionTokenService
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
    fun `registerUser returns user and session`() =
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
            assertTrue(response.session.accessToken.isNotBlank())
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
    fun `getMe requires authentication`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.UNAUTHENTICATED) {
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
                withIdentityUser(registered.user.id) {
                    rpc.getMe(GetMeRequest.getDefaultInstance())
                }

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
    fun `updateProfile requires authentication`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.UNAUTHENTICATED) {
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
                    withIdentityUser(registered.user.id) {
                        rpc.updateProfile(
                            UpdateProfileRequest
                                .newBuilder()
                                .setAvatarMediaId("bad-avatar")
                                .build(),
                        )
                    }
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
                refreshSessionRepository = repository.refreshSessions,
                userOutbox = repository.outbox,
                transactionRunner = ImmediateTransactionRunner,
                passwordHasher = PasswordHasher(),
                tokenService = JwtTokenService(secret = "test-secret", clock = fixedClock),
                refreshSessionTokenService = RefreshSessionTokenService(),
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

private fun <T> withIdentityUser(
    userId: String,
    block: suspend () -> T,
): T =
    GrpcAuthContext
        .withPrincipal(AuthenticatedPrincipal(subject = userId))
        .call<T> { runBlocking { block() } }

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
) : IdentityUserRepository {
    var savedUser: IdentityUser? = null
    val refreshSessions = RecordingRefreshSessionRepository()
    val outbox = RecordingIdentityUserOutbox()

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

private class RecordingRefreshSessionRepository : RefreshSessionRepository {
    private val sessions = mutableListOf<RefreshSession>()

    override fun save(session: RefreshSession) {
        sessions += session
    }

    override fun findByRefreshTokenHash(refreshTokenHash: String): RefreshSession? =
        sessions.lastOrNull { it.refreshTokenHash == refreshTokenHash }

    override fun revoke(
        sessionId: RefreshSessionId,
        revokedAt: Instant,
    ): Boolean {
        val index = sessions.indexOfFirst { it.id == sessionId && it.revokedAt == null }
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
        val index = sessions.indexOfFirst { it.refreshTokenHash == refreshTokenHash && it.revokedAt == null }
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

@Suppress("unused")
private fun sampleUser(): IdentityUser {
    val now = Instant.parse("2026-05-18T00:00:00Z")
    return IdentityUser(
        id = UserId(Uuid.random()),
        email = Email("alice@example.com"),
        username = Username("alice"),
        displayName = DisplayName("Alice"),
        passwordHash = PasswordHash(iterations = 1, hash = "hash", salt = "salt"),
        avatarMediaId = null,
        status = IdentityUserStatus.ACTIVE,
        createdAt = now,
        updatedAt = now,
        deactivatedAt = null,
    )
}
