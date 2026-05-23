package jp.xhw.mikke.services.friendship.application

import jp.xhw.mikke.events.friendship.FriendshipEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.services.friendship.application.exception.DuplicateFriendRequestException
import jp.xhw.mikke.services.friendship.application.exception.FriendRequestNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipNotAllowedException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.InvalidFriendshipInputException
import jp.xhw.mikke.services.friendship.application.port.BlockRepository
import jp.xhw.mikke.services.friendship.application.port.FriendRequestRepository
import jp.xhw.mikke.services.friendship.application.port.FriendshipOutbox
import jp.xhw.mikke.services.friendship.application.port.FriendshipRepository
import jp.xhw.mikke.services.friendship.application.service.FriendshipService
import jp.xhw.mikke.services.friendship.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class FriendshipServiceTest {
    @Test
    fun `send friend request rejects self action`() {
        val alice = UserId(Uuid.random())
        val service = createService(RecordingFriendshipStores())

        assertThrows(InvalidFriendshipInputException::class.java) {
            service.sendFriendRequest(alice, alice)
        }
    }

    @Test
    fun `send accept flow creates friendship and outbox events`() {
        val stores = RecordingFriendshipStores()
        val service = createService(stores)
        val alice = UserId(Uuid.random())
        val bob = UserId(Uuid.random())

        val request = service.sendFriendRequest(alice, bob)
        val friendship = service.acceptFriendRequest(bob, request.id)

        assertEquals(FriendshipStatus.ACTIVE, friendship.status)
        assertEquals(FriendRequestStatus.ACCEPTED, stores.friendRequests.byId(request.id)?.status)
        assertEquals(2, stores.outbox.entries.size)
        assertEquals(FriendshipEventTypes.REQUESTED, stores.outbox.entries[0].eventType)
        assertEquals(FriendshipEventTypes.ACCEPTED, stores.outbox.entries[1].eventType)
    }

    @Test
    fun `duplicate pending request is rejected`() {
        val stores = RecordingFriendshipStores()
        val service = createService(stores)
        val alice = UserId(Uuid.random())
        val bob = UserId(Uuid.random())

        service.sendFriendRequest(alice, bob)

        assertThrows(DuplicateFriendRequestException::class.java) {
            service.sendFriendRequest(alice, bob)
        }
    }

    @Test
    fun `only receiver can accept request`() {
        val stores = RecordingFriendshipStores()
        val service = createService(stores)
        val alice = UserId(Uuid.random())
        val bob = UserId(Uuid.random())

        val request = service.sendFriendRequest(alice, bob)

        assertThrows(FriendshipNotAllowedException::class.java) {
            service.acceptFriendRequest(alice, request.id)
        }
    }

    @Test
    fun `block removes friendship and prevents requests`() {
        val stores = RecordingFriendshipStores()
        val service = createService(stores)
        val alice = UserId(Uuid.random())
        val bob = UserId(Uuid.random())

        val request = service.sendFriendRequest(alice, bob)
        service.acceptFriendRequest(bob, request.id)

        service.blockUser(alice, bob)

        assertNull(stores.friendships.findActiveBetween(alice, bob))
        assertNotNull(stores.blocks.find(alice, bob))
        assertThrows(FriendshipNotAllowedException::class.java) {
            service.sendFriendRequest(bob, alice)
        }
    }

    @Test
    fun `friendship summary reflects friends and blocked states`() {
        val stores = RecordingFriendshipStores()
        val service = createService(stores)
        val alice = UserId(Uuid.random())
        val bob = UserId(Uuid.random())

        val none = service.getFriendshipSummary(alice, bob)
        assertEquals(FriendshipRelationStatus.NONE, none.relationStatus)
        assertTrue(none.canSendRequest)

        val request = service.sendFriendRequest(alice, bob)
        val sent = service.getFriendshipSummary(alice, bob)
        assertEquals(FriendshipRelationStatus.REQUEST_SENT, sent.relationStatus)
        assertFalse(sent.canViewPosts)

        val received = service.getFriendshipSummary(bob, alice)
        assertEquals(FriendshipRelationStatus.REQUEST_RECEIVED, received.relationStatus)

        service.acceptFriendRequest(bob, request.id)
        val friends = service.getFriendshipSummary(alice, bob)
        assertEquals(FriendshipRelationStatus.FRIENDS, friends.relationStatus)
        assertTrue(friends.canViewPosts)

        service.blockUser(alice, bob)
        val blocked = service.getFriendshipSummary(alice, bob)
        assertEquals(FriendshipRelationStatus.BLOCKED_BY_ME, blocked.relationStatus)
        assertFalse(blocked.canViewPosts)
    }

    @Test
    fun `check can view posts allows only friends`() {
        val stores = RecordingFriendshipStores()
        val service = createService(stores)
        val alice = UserId(Uuid.random())
        val bob = UserId(Uuid.random())

        assertFalse(service.checkCanViewUserPosts(alice, bob).canView)

        val request = service.sendFriendRequest(alice, bob)
        service.acceptFriendRequest(bob, request.id)

        assertTrue(service.checkCanViewUserPosts(alice, bob).canView)
        assertTrue(service.checkCanViewUserPosts(bob, alice).canView)
    }

    @Test
    fun `list friends returns active friendships`() {
        val stores = RecordingFriendshipStores()
        val service = createService(stores)
        val alice = UserId(Uuid.random())
        val bob = UserId(Uuid.random())
        val carol = UserId(Uuid.random())

        val requestToBob = service.sendFriendRequest(alice, bob)
        service.acceptFriendRequest(bob, requestToBob.id)

        val requestToCarol = service.sendFriendRequest(alice, carol)
        service.acceptFriendRequest(carol, requestToCarol.id)

        val page = PageRequestInput(pageSize = 10).validate()
        val friends = service.listFriends(alice, page)

        assertEquals(2, friends.items.size)
        assertTrue(friends.items.contains(bob))
        assertTrue(friends.items.contains(carol))
    }

    @Test
    fun `requested outbox payload contains expected fields`() {
        val stores = RecordingFriendshipStores()
        val service = createService(stores)
        val alice = UserId(Uuid.random())
        val bob = UserId(Uuid.random())

        service.sendFriendRequest(alice, bob)

        val payload =
            Json
                .parseToJsonElement(
                    stores.outbox.entries
                        .single()
                        .payloadJson,
                ).jsonObject
        assertTrue(payload.containsKey("friend_request_id"))
        assertTrue(payload.containsKey("sender_user_id"))
        assertTrue(payload.containsKey("receiver_user_id"))
        assertTrue(
            payload
                .getValue("sender_user_id")
                .jsonPrimitive.content
                .isNotEmpty(),
        )
    }

    private fun createService(stores: RecordingFriendshipStores): FriendshipService =
        FriendshipService(
            friendRequestRepository = stores.friendRequests,
            friendshipRepository = stores.friendships,
            blockRepository = stores.blocks,
            friendshipOutbox = stores.outbox,
            transactionRunner = ImmediateTransactionRunner,
            clock = fixedClock,
        )

    private companion object {
        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-05-18T00:00:00Z")
            }
    }
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private class RecordingFriendshipStores {
    val friendRequests = RecordingFriendRequestRepository()
    val friendships = RecordingFriendshipRepository()
    val blocks = RecordingBlockRepository()
    val outbox = RecordingFriendshipOutbox()
}

private class RecordingFriendRequestRepository : FriendRequestRepository {
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

    fun byId(id: FriendRequestId): FriendRequest? = findById(id)

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
            .sortedWith(compareByDescending<FriendRequest> { it.createdAt }.thenByDescending { it.id.value })
            .take(limit)

    override fun listOutgoing(
        senderUserId: UserId,
        limit: Int,
        cursor: jp.xhw.mikke.platform.pagination.CreatedAtIdCursor?,
    ): List<FriendRequest> =
        requests
            .filter { it.senderUserId == senderUserId && it.status == FriendRequestStatus.PENDING }
            .sortedWith(compareByDescending<FriendRequest> { it.createdAt }.thenByDescending { it.id.value })
            .take(limit)

    override fun cancelPendingBetween(
        firstUserId: UserId,
        secondUserId: UserId,
        canceledAt: Instant,
    ): Int {
        var count = 0
        requests.indices.forEach { index ->
            val request = requests[index]
            if (
                request.status == FriendRequestStatus.PENDING &&
                (
                    (request.senderUserId == firstUserId && request.receiverUserId == secondUserId) ||
                        (request.senderUserId == secondUserId && request.receiverUserId == firstUserId)
                )
            ) {
                requests[index] =
                    request.copy(
                        status = FriendRequestStatus.CANCELED,
                        canceledAt = canceledAt,
                    )
                count++
            }
        }
        return count
    }
}

private class RecordingFriendshipRepository : FriendshipRepository {
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
        friendships[index] =
            friendships[index].copy(
                status = FriendshipStatus.REMOVED,
                removedAt = removedAt,
            )
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
            }.sortedWith(compareByDescending<Friendship> { it.createdAt }.thenByDescending { it.id.value })
            .take(limit)
}

private class RecordingBlockRepository : BlockRepository {
    private val blocks = mutableListOf<BlockRelation>()

    override fun save(block: BlockRelation) {
        blocks += block
    }

    override fun delete(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): Boolean {
        val removed =
            blocks.removeIf {
                it.blockerUserId == blockerUserId && it.blockedUserId == blockedUserId
            }
        return removed
    }

    override fun find(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): BlockRelation? = blocks.lastOrNull { it.blockerUserId == blockerUserId && it.blockedUserId == blockedUserId }
}

private class RecordingFriendshipOutbox : FriendshipOutbox {
    val entries = mutableListOf<OutboxEntry>()

    override fun appendFriendRequestRequested(request: FriendRequest) {
        entries +=
            outboxEntry(
                eventType = FriendshipEventTypes.REQUESTED,
                aggregateId = request.id.value,
                payloadJson =
                    """
                    {
                      "friend_request_id":"${request.id.value}",
                      "sender_user_id":"${request.senderUserId.value}",
                      "receiver_user_id":"${request.receiverUserId.value}",
                      "created_at":"${request.createdAt}"
                    }
                    """.trimIndent(),
            )
    }

    override fun appendFriendRequestAccepted(
        request: FriendRequest,
        friendship: Friendship,
    ) {
        entries += outboxEntry(FriendshipEventTypes.ACCEPTED, friendship.id.value)
    }

    override fun appendFriendRequestRejected(request: FriendRequest) {
        entries += outboxEntry(FriendshipEventTypes.REJECTED, request.id.value)
    }

    override fun appendFriendRequestCanceled(request: FriendRequest) {
        entries += outboxEntry(FriendshipEventTypes.CANCELED, request.id.value)
    }

    override fun appendFriendshipRemoved(friendship: Friendship) {
        entries += outboxEntry(FriendshipEventTypes.REMOVED, friendship.id.value)
    }

    override fun appendUserBlocked(block: BlockRelation) {
        entries += outboxEntry(FriendshipEventTypes.BLOCKED, block.blockerUserId.value)
    }

    override fun appendUserUnblocked(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ) {
        entries += outboxEntry(FriendshipEventTypes.UNBLOCKED, blockerUserId.value)
    }

    private fun outboxEntry(
        eventType: String,
        aggregateId: Uuid,
        payloadJson: String = "{}",
    ): OutboxEntry =
        OutboxEntry(
            id = Uuid.random(),
            eventType = eventType,
            aggregateType = "friendship",
            aggregateId = aggregateId,
            payloadJson = payloadJson,
            createdAt = Instant.parse("2026-05-18T00:00:00Z"),
        )
}
