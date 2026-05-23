package jp.xhw.mikke.services.friendship.infrastructure.outbox

import jp.xhw.mikke.services.friendship.model.FriendRequest
import jp.xhw.mikke.services.friendship.model.FriendRequestId
import jp.xhw.mikke.services.friendship.model.FriendRequestStatus
import jp.xhw.mikke.services.friendship.model.Friendship
import jp.xhw.mikke.services.friendship.model.FriendshipId
import jp.xhw.mikke.services.friendship.model.FriendshipStatus
import jp.xhw.mikke.services.friendship.model.UserId
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedFriendshipOutboxTest {
    @Test
    fun `append accepted request requires respondedAt`() {
        val outbox = ExposedFriendshipOutbox()
        val request = friendRequest(status = FriendRequestStatus.ACCEPTED, respondedAt = null)
        val friendship = friendship(request.senderUserId, request.receiverUserId)

        assertThrows(IllegalArgumentException::class.java) {
            outbox.appendFriendRequestAccepted(request, friendship)
        }
    }

    @Test
    fun `append rejected request requires respondedAt`() {
        val outbox = ExposedFriendshipOutbox()
        val request = friendRequest(status = FriendRequestStatus.REJECTED, respondedAt = null)

        assertThrows(IllegalArgumentException::class.java) {
            outbox.appendFriendRequestRejected(request)
        }
    }

    @Test
    fun `append canceled request requires canceledAt`() {
        val outbox = ExposedFriendshipOutbox()
        val request = friendRequest(status = FriendRequestStatus.CANCELED, canceledAt = null)

        assertThrows(IllegalArgumentException::class.java) {
            outbox.appendFriendRequestCanceled(request)
        }
    }

    @Test
    fun `append removed friendship requires removedAt`() {
        val outbox = ExposedFriendshipOutbox()
        val friendship = friendship(UserId(Uuid.random()), UserId(Uuid.random()))

        assertThrows(IllegalArgumentException::class.java) {
            outbox.appendFriendshipRemoved(friendship)
        }
    }

    private fun friendRequest(
        status: FriendRequestStatus,
        respondedAt: Instant? = null,
        canceledAt: Instant? = null,
    ): FriendRequest =
        FriendRequest(
            id = FriendRequestId(Uuid.random()),
            senderUserId = UserId(Uuid.random()),
            receiverUserId = UserId(Uuid.random()),
            status = status,
            createdAt = Instant.parse("2026-05-18T00:00:00Z"),
            respondedAt = respondedAt,
            canceledAt = canceledAt,
        )

    private fun friendship(
        firstUserId: UserId,
        secondUserId: UserId,
    ): Friendship {
        val lowUserId = if (firstUserId.value < secondUserId.value) firstUserId else secondUserId
        val highUserId = if (firstUserId.value < secondUserId.value) secondUserId else firstUserId

        return Friendship(
            id = FriendshipId(Uuid.random()),
            userLowId = lowUserId,
            userHighId = highUserId,
            status = FriendshipStatus.ACTIVE,
            createdAt = Instant.parse("2026-05-18T00:00:00Z"),
            removedAt = null,
        )
    }
}
