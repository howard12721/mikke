package jp.xhw.mikke.services.friendship

import io.grpc.Status
import jp.xhw.mikke.common.v1.PageRequest
import jp.xhw.mikke.friendship.v1.*
import jp.xhw.mikke.platform.auth.AuthenticatedPrincipal
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthContext
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.grpc.InternalCallerContext
import jp.xhw.mikke.platform.grpc.InternalRpcContext
import jp.xhw.mikke.platform.grpc.toGrpcStatusRuntimeException
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.services.friendship.application.exception.DuplicateFriendRequestException
import jp.xhw.mikke.services.friendship.application.exception.FriendRequestNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipApplicationException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipStateException
import jp.xhw.mikke.services.friendship.application.port.BlockRepository
import jp.xhw.mikke.services.friendship.application.port.FriendRequestRepository
import jp.xhw.mikke.services.friendship.application.port.FriendshipOutbox
import jp.xhw.mikke.services.friendship.application.port.FriendshipRepository
import jp.xhw.mikke.services.friendship.application.service.FriendshipService
import jp.xhw.mikke.services.friendship.model.*
import jp.xhw.mikke.services.friendship.model.BlockRelation
import jp.xhw.mikke.services.friendship.model.FriendRequest
import jp.xhw.mikke.services.friendship.model.FriendRequestStatus
import jp.xhw.mikke.services.friendship.model.Friendship
import jp.xhw.mikke.services.friendship.model.FriendshipRelationStatus
import jp.xhw.mikke.services.friendship.model.FriendshipStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.logging.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class FriendshipServiceRpcTest {
    @Test
    fun `sendFriendRequest returns friend request for authenticated user`() =
        runBlocking {
            val stores = RecordingFriendshipStores()
            val rpc = createRpc(stores)
            val alice = UserId(Uuid.random())
            val bob = UserId(Uuid.random())

            val response =
                withUser(alice) {
                    rpc.sendFriendRequest(
                        SendFriendRequestRequest
                            .newBuilder()
                            .setReceiverUserId(" ${bob.value} ")
                            .build(),
                    )
                }

            assertEquals(alice.value.toString(), response.friendRequest.senderUserId)
            assertEquals(bob.value.toString(), response.friendRequest.receiverUserId)
            assertEquals(1, stores.outbox.entries.size)
        }

    @Test
    fun `sendFriendRequest requires authentication`() =
        runBlocking {
            val rpc = createRpc()

            val error =
                assertStatus(Status.Code.UNAUTHENTICATED) {
                    rpc.sendFriendRequest(
                        SendFriendRequestRequest
                            .newBuilder()
                            .setReceiverUserId(Uuid.random().toString())
                            .build(),
                    )
                }

            assertEquals("Authentication required", error.description)
        }

    @Test
    fun `sendFriendRequest maps invalid receiver id to invalid argument`() =
        runBlocking {
            val rpc = createRpc()

            val error =
                assertStatus(Status.Code.INVALID_ARGUMENT) {
                    withUser(UserId(Uuid.random())) {
                        rpc.sendFriendRequest(
                            SendFriendRequestRequest
                                .newBuilder()
                                .setReceiverUserId("not-a-uuid")
                                .build(),
                        )
                    }
                }

            assertEquals("user_id must be a valid UUID", error.description)
        }

    @Test
    fun `sendFriendRequest maps self request to invalid argument`(): Unit =
        runBlocking {
            val rpc = createRpc()
            val alice = UserId(Uuid.random())

            assertStatus(Status.Code.INVALID_ARGUMENT) {
                withUser(alice) {
                    rpc.sendFriendRequest(
                        SendFriendRequestRequest
                            .newBuilder()
                            .setReceiverUserId(alice.value.toString())
                            .build(),
                    )
                }
            }
        }

    @Test
    fun `sendFriendRequest maps duplicate pending request to already exists`(): Unit =
        runBlocking {
            val rpc = createRpc()
            val alice = UserId(Uuid.random())
            val bob = UserId(Uuid.random())
            val request =
                SendFriendRequestRequest
                    .newBuilder()
                    .setReceiverUserId(bob.value.toString())
                    .build()

            withUser(alice) { rpc.sendFriendRequest(request) }

            assertStatus(Status.Code.ALREADY_EXISTS) {
                withUser(alice) { rpc.sendFriendRequest(request) }
            }
        }

    @Test
    fun `acceptFriendRequest maps wrong receiver to permission denied`(): Unit =
        runBlocking {
            val rpc = createRpc()
            val alice = UserId(Uuid.random())
            val bob = UserId(Uuid.random())
            val request =
                withUser(alice) {
                    rpc.sendFriendRequest(
                        SendFriendRequestRequest
                            .newBuilder()
                            .setReceiverUserId(bob.value.toString())
                            .build(),
                    )
                }

            assertStatus(Status.Code.PERMISSION_DENIED) {
                withUser(alice) {
                    rpc.acceptFriendRequest(
                        AcceptFriendRequestRequest
                            .newBuilder()
                            .setFriendRequestId(request.friendRequest.id)
                            .build(),
                    )
                }
            }
        }

    @Test
    fun `acceptFriendRequest maps missing request to not found`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.NOT_FOUND) {
                withUser(UserId(Uuid.random())) {
                    rpc.acceptFriendRequest(
                        AcceptFriendRequestRequest
                            .newBuilder()
                            .setFriendRequestId(Uuid.random().toString())
                            .build(),
                    )
                }
            }
        }

    @Test
    fun `acceptFriendRequest maps non pending request to failed precondition`(): Unit =
        runBlocking {
            val rpc = createRpc()
            val alice = UserId(Uuid.random())
            val bob = UserId(Uuid.random())
            val request =
                withUser(alice) {
                    rpc.sendFriendRequest(
                        SendFriendRequestRequest
                            .newBuilder()
                            .setReceiverUserId(bob.value.toString())
                            .build(),
                    )
                }

            withUser(bob) {
                rpc.acceptFriendRequest(
                    AcceptFriendRequestRequest
                        .newBuilder()
                        .setFriendRequestId(request.friendRequest.id)
                        .build(),
                )
            }

            assertStatus(Status.Code.FAILED_PRECONDITION) {
                withUser(bob) {
                    rpc.acceptFriendRequest(
                        AcceptFriendRequestRequest
                            .newBuilder()
                            .setFriendRequestId(request.friendRequest.id)
                            .build(),
                    )
                }
            }
        }

    @Test
    fun `acceptFriendRequest maps duplicate friendship save to failed precondition`(): Unit =
        runBlocking {
            val stores = RecordingFriendshipStores(friendships = DuplicatePairOnSaveFriendshipRepository())
            val rpc = createRpc(stores)
            val alice = UserId(Uuid.random())
            val bob = UserId(Uuid.random())
            val request =
                withUser(alice) {
                    rpc.sendFriendRequest(
                        SendFriendRequestRequest
                            .newBuilder()
                            .setReceiverUserId(bob.value.toString())
                            .build(),
                    )
                }

            assertStatus(Status.Code.FAILED_PRECONDITION) {
                withUser(bob) {
                    rpc.acceptFriendRequest(
                        AcceptFriendRequestRequest
                            .newBuilder()
                            .setFriendRequestId(request.friendRequest.id)
                            .build(),
                    )
                }
            }
        }

    @Test
    fun `removeFriend maps missing friendship to not found`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.NOT_FOUND) {
                withUser(UserId(Uuid.random())) {
                    rpc.removeFriend(
                        RemoveFriendRequest
                            .newBuilder()
                            .setFriendUserId(Uuid.random().toString())
                            .build(),
                    )
                }
            }
        }

    @Test
    fun `unblockUser maps missing block to not found`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.NOT_FOUND) {
                withUser(UserId(Uuid.random())) {
                    rpc.unblockUser(
                        UnblockUserRequest
                            .newBuilder()
                            .setBlockedUserId(Uuid.random().toString())
                            .build(),
                    )
                }
            }
        }

    @Test
    fun `listFriends maps invalid page request to invalid argument`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.INVALID_ARGUMENT) {
                rpc.listFriends(
                    ListFriendsRequest
                        .newBuilder()
                        .setTargetUserId(Uuid.random().toString())
                        .setPage(PageRequest.newBuilder().setPageSize(-1))
                        .build(),
                )
            }
        }

    @Test
    fun `batchGetFriendshipSummaries requires authentication`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.UNAUTHENTICATED) {
                rpc.batchGetFriendshipSummaries(
                    BatchGetFriendshipSummariesRequest
                        .newBuilder()
                        .addTargetUserIds(Uuid.random().toString())
                        .build(),
                )
            }
        }

    @Test
    fun `checkCanViewUserPosts returns relation status for authenticated viewer`() =
        runBlocking {
            val rpc = createRpc()
            val alice = UserId(Uuid.random())
            val bob = UserId(Uuid.random())

            val response =
                withUser(alice) {
                    rpc.checkCanViewUserPosts(
                        CheckCanViewUserPostsRequest
                            .newBuilder()
                            .setOwnerUserId(bob.value.toString())
                            .build(),
                    )
                }

            assertFalse(response.canView)
            assertEquals(
                jp.xhw.mikke.friendship.v1.FriendshipRelationStatus.FRIENDSHIP_RELATION_STATUS_NONE,
                response.relationStatus,
            )
        }

    @Test
    fun `checkCanViewUserPostsForViewer requires internal caller context`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.UNAUTHENTICATED) {
                rpc.checkCanViewUserPostsForViewer(
                    CheckCanViewUserPostsForViewerRequest
                        .newBuilder()
                        .setViewerUserId(Uuid.random().toString())
                        .setOwnerUserId(Uuid.random().toString())
                        .build(),
                )
            }
        }

    @Test
    fun `checkCanViewUserPostsForViewer rejects disallowed internal caller`(): Unit =
        runBlocking {
            val rpc = createRpc()

            assertStatus(Status.Code.PERMISSION_DENIED) {
                withInternalCaller("notification-service") {
                    rpc.checkCanViewUserPostsForViewer(
                        CheckCanViewUserPostsForViewerRequest
                            .newBuilder()
                            .setViewerUserId(Uuid.random().toString())
                            .setOwnerUserId(Uuid.random().toString())
                            .build(),
                    )
                }
            }
        }

    @Test
    fun `checkCanViewUserPostsForViewer allows configured internal caller`() =
        runBlocking {
            val rpc = createRpc()
            val viewer = UserId(Uuid.random())
            val owner = UserId(Uuid.random())

            val response =
                withInternalCaller("post-service") {
                    rpc.checkCanViewUserPostsForViewer(
                        CheckCanViewUserPostsForViewerRequest
                            .newBuilder()
                            .setViewerUserId(viewer.value.toString())
                            .setOwnerUserId(owner.value.toString())
                            .build(),
                    )
                }

            assertFalse(response.canView)
            assertEquals(
                jp.xhw.mikke.friendship.v1.FriendshipRelationStatus.FRIENDSHIP_RELATION_STATUS_NONE,
                response.relationStatus,
            )
        }

    @Test
    fun `unexpected exception is mapped to internal`() =
        runBlocking {
            val rpc = createRpc(RecordingFriendshipStores(friendRequests = ThrowingFriendRequestRepository()))

            val error =
                assertStatus(Status.Code.INTERNAL) {
                    withUser(UserId(Uuid.random())) {
                        rpc.sendFriendRequest(
                            SendFriendRequestRequest
                                .newBuilder()
                                .setReceiverUserId(Uuid.random().toString())
                                .build(),
                        )
                    }
                }

            assertEquals("Internal friendship service error", error.description)
        }

    private fun createRpc(stores: RecordingFriendshipStores = RecordingFriendshipStores()): FriendshipServiceRpc =
        FriendshipServiceRpc(
            FriendshipService(
                friendRequestRepository = stores.friendRequests,
                friendshipRepository = stores.friendships,
                blockRepository = stores.blocks,
                friendshipOutbox = stores.outbox,
                transactionRunner = ImmediateTransactionRunner,
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

private fun <T> withUser(
    userId: UserId,
    block: suspend () -> T,
): T =
    GrpcAuthContext
        .withPrincipal(AuthenticatedPrincipal(subject = userId.value.toString()))
        .call<T> { runBlocking { block() } }

private fun <T> withInternalCaller(
    serviceName: String,
    block: suspend () -> T,
): T =
    InternalRpcContext
        .withCaller(InternalCallerContext(callerService = serviceName, correlationId = null))
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
                logger = Logger.getLogger(FriendshipServiceRpcTest::class.java.name),
                serviceName = "friendship-service",
                internalErrorDescription = "Internal friendship service error",
                domainExceptionMapper = { throwable ->
                    (throwable as? FriendshipApplicationException)?.toGrpcStatus()
                },
            ),
        )
    assertEquals(expectedCode, status.code)
    return status
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private class RecordingFriendshipStores(
    val friendRequests: FriendRequestRepository = RecordingFriendRequestRepository(),
    val friendships: RecordingFriendshipRepository = RecordingFriendshipRepository(),
    val blocks: RecordingBlockRepository = RecordingBlockRepository(),
    val outbox: RecordingFriendshipOutbox = RecordingFriendshipOutbox(),
)

private open class RecordingFriendRequestRepository : FriendRequestRepository {
    private val requests = mutableListOf<FriendRequest>()

    override fun save(request: FriendRequest) {
        if (findPendingBetween(request.senderUserId, request.receiverUserId) != null) {
            throw DuplicateFriendRequestException()
        }
        requests += request
    }

    override fun update(request: FriendRequest) {
        val index = requests.indexOfFirst { it.id == request.id }
        if (index < 0) {
            throw FriendRequestNotFoundException()
        }
        requests[index] = request
    }

    override fun findById(id: FriendRequestId): FriendRequest? = requests.lastOrNull { it.id == id }

    override fun findPendingBetween(
        firstUserId: UserId,
        secondUserId: UserId,
    ): FriendRequest? =
        requests.lastOrNull {
            it.status == FriendRequestStatus.PENDING &&
                (
                    (it.senderUserId == firstUserId && it.receiverUserId == secondUserId) ||
                        (it.senderUserId == secondUserId && it.receiverUserId == firstUserId)
                )
        }

    override fun listIncoming(
        receiverUserId: UserId,
        limit: Int,
        cursor: jp.xhw.mikke.platform.pagination.CreatedAtIdCursor?,
    ): List<FriendRequest> =
        requests
            .filter { it.receiverUserId == receiverUserId && it.status == FriendRequestStatus.PENDING }
            .take(limit)

    override fun listOutgoing(
        senderUserId: UserId,
        limit: Int,
        cursor: jp.xhw.mikke.platform.pagination.CreatedAtIdCursor?,
    ): List<FriendRequest> =
        requests
            .filter { it.senderUserId == senderUserId && it.status == FriendRequestStatus.PENDING }
            .take(limit)

    override fun cancelPendingBetween(
        firstUserId: UserId,
        secondUserId: UserId,
        canceledAt: Instant,
    ): Int {
        var canceled = 0
        requests.indices.forEach { index ->
            val request = requests[index]
            val matches =
                (request.senderUserId == firstUserId && request.receiverUserId == secondUserId) ||
                    (request.senderUserId == secondUserId && request.receiverUserId == firstUserId)
            if (request.status == FriendRequestStatus.PENDING && matches) {
                requests[index] = request.copy(status = FriendRequestStatus.CANCELED, canceledAt = canceledAt)
                canceled++
            }
        }
        return canceled
    }
}

private class ThrowingFriendRequestRepository : RecordingFriendRequestRepository() {
    override fun findPendingBetween(
        firstUserId: UserId,
        secondUserId: UserId,
    ): FriendRequest = throw IllegalStateException("database failed")
}

private open class RecordingFriendshipRepository : FriendshipRepository {
    private val friendships = mutableListOf<Friendship>()

    override fun save(friendship: Friendship) {
        friendships += friendship
    }

    override fun update(friendship: Friendship) {
        val index = friendships.indexOfFirst { it.id == friendship.id }
        if (index < 0) {
            throw FriendshipNotFoundException()
        }
        friendships[index] = friendship
    }

    override fun findByPair(pair: NormalizedUserPair): Friendship? =
        friendships.lastOrNull { it.userLowId == pair.low && it.userHighId == pair.high }

    override fun findActiveBetween(
        firstUserId: UserId,
        secondUserId: UserId,
    ): Friendship? = findByPair(NormalizedUserPair.of(firstUserId, secondUserId))?.takeIf { it.status == FriendshipStatus.ACTIVE }

    override fun markRemoved(
        id: FriendshipId,
        removedAt: Instant,
    ): Boolean {
        val index = friendships.indexOfFirst { it.id == id && it.status == FriendshipStatus.ACTIVE }
        if (index < 0) {
            return false
        }
        friendships[index] = friendships[index].copy(status = FriendshipStatus.REMOVED, removedAt = removedAt)
        return true
    }

    override fun listActiveFriends(
        userId: UserId,
        limit: Int,
        cursor: jp.xhw.mikke.platform.pagination.CreatedAtIdCursor?,
    ): List<Friendship> =
        friendships
            .filter {
                it.status == FriendshipStatus.ACTIVE &&
                    (it.userLowId == userId || it.userHighId == userId)
            }.take(limit)
}

private class DuplicatePairOnSaveFriendshipRepository : RecordingFriendshipRepository() {
    override fun save(friendship: Friendship): Unit = throw FriendshipStateException("Friendship already exists")
}

private class RecordingBlockRepository : BlockRepository {
    private val blocks = mutableListOf<BlockRelation>()

    override fun save(block: BlockRelation) {
        blocks += block
    }

    override fun delete(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): Boolean = blocks.removeIf { it.blockerUserId == blockerUserId && it.blockedUserId == blockedUserId }

    override fun find(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): BlockRelation? = blocks.lastOrNull { it.blockerUserId == blockerUserId && it.blockedUserId == blockedUserId }
}

private class RecordingFriendshipOutbox : FriendshipOutbox {
    val entries = mutableListOf<OutboxEntry>()

    override fun appendFriendRequestRequested(request: FriendRequest) {
        entries += outboxEntry(request.id.value)
    }

    override fun appendFriendRequestAccepted(
        request: FriendRequest,
        friendship: Friendship,
    ) {
        entries += outboxEntry(friendship.id.value)
    }

    override fun appendFriendRequestRejected(request: FriendRequest) {
        entries += outboxEntry(request.id.value)
    }

    override fun appendFriendRequestCanceled(request: FriendRequest) {
        entries += outboxEntry(request.id.value)
    }

    override fun appendFriendshipRemoved(friendship: Friendship) {
        entries += outboxEntry(friendship.id.value)
    }

    override fun appendUserBlocked(block: BlockRelation) {
        entries += outboxEntry(block.blockerUserId.value)
    }

    override fun appendUserUnblocked(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ) {
        entries += outboxEntry(blockerUserId.value)
    }

    private fun outboxEntry(aggregateId: Uuid): OutboxEntry =
        OutboxEntry(
            id = Uuid.random(),
            eventType = "test",
            aggregateType = FriendshipRelationStatus.NONE.name,
            aggregateId = aggregateId,
            payloadJson = "{}",
            createdAt = Instant.parse("2026-05-18T00:00:00Z"),
        )
}
