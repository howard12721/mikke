package jp.xhw.mikke.services.friendship.application.service

import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.platform.pagination.PageSlice
import jp.xhw.mikke.platform.pagination.ValidatedPageRequest
import jp.xhw.mikke.platform.pagination.buildPageSlice
import jp.xhw.mikke.services.friendship.application.command.*
import jp.xhw.mikke.services.friendship.application.exception.*
import jp.xhw.mikke.services.friendship.application.port.BlockRepository
import jp.xhw.mikke.services.friendship.application.port.FriendRequestRepository
import jp.xhw.mikke.services.friendship.application.port.FriendshipOutbox
import jp.xhw.mikke.services.friendship.application.port.FriendshipRepository
import jp.xhw.mikke.services.friendship.model.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

class FriendshipService(
    private val friendRequestRepository: FriendRequestRepository,
    private val friendshipRepository: FriendshipRepository,
    private val blockRepository: BlockRepository,
    private val friendshipOutbox: FriendshipOutbox,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock = Clock.System,
) {
    fun sendFriendRequest(
        senderUserId: UserId,
        receiverUserId: UserId,
    ): FriendRequest =
        sendFriendRequest(
            SendFriendRequestCommand(
                senderUserId = senderUserId,
                receiverUserId = receiverUserId,
            ),
        )

    fun sendFriendRequest(command: SendFriendRequestCommand): FriendRequest =
        transactionRunner.runInTransaction {
            ensureNotSelf(command.senderUserId, command.receiverUserId)
            ensureNotBlocked(command.senderUserId, command.receiverUserId)

            if (friendshipRepository.findActiveBetween(command.senderUserId, command.receiverUserId) != null) {
                throw FriendshipStateException("Users are already friends")
            }

            if (friendRequestRepository.findPendingBetween(command.senderUserId, command.receiverUserId) != null) {
                throw DuplicateFriendRequestException()
            }

            val now = clock.now()
            val request =
                FriendRequest(
                    id = FriendRequestId(Uuid.random()),
                    senderUserId = command.senderUserId,
                    receiverUserId = command.receiverUserId,
                    status = FriendRequestStatus.PENDING,
                    createdAt = now,
                    respondedAt = null,
                    canceledAt = null,
                )

            friendRequestRepository.save(request)
            friendshipOutbox.appendFriendRequestRequested(request)
            request
        }

    fun acceptFriendRequest(
        receiverUserId: UserId,
        friendRequestId: FriendRequestId,
    ): Friendship =
        acceptFriendRequest(
            AcceptFriendRequestCommand(
                receiverUserId = receiverUserId,
                friendRequestId = friendRequestId,
            ),
        )

    fun acceptFriendRequest(command: AcceptFriendRequestCommand): Friendship =
        transactionRunner.runInTransaction {
            val request =
                friendRequestRepository.findById(command.friendRequestId)
                    ?: throw FriendRequestNotFoundException()

            if (request.receiverUserId != command.receiverUserId) {
                throw FriendshipNotAllowedException("Only the receiver can accept this friend request")
            }

            if (request.status != FriendRequestStatus.PENDING) {
                throw FriendshipStateException("Friend request is not pending")
            }

            ensureNotBlocked(request.senderUserId, request.receiverUserId)

            val now = clock.now()
            val respondedRequest =
                request.copy(
                    status = FriendRequestStatus.ACCEPTED,
                    respondedAt = now,
                )
            friendRequestRepository.update(respondedRequest)

            val pair = NormalizedUserPair.of(request.senderUserId, request.receiverUserId)
            val existing = friendshipRepository.findByPair(pair)
            val friendship =
                if (existing != null) {
                    existing
                        .copy(
                            status = FriendshipStatus.ACTIVE,
                            createdAt = now,
                            removedAt = null,
                        ).also(friendshipRepository::update)
                } else {
                    Friendship(
                        id = FriendshipId(Uuid.random()),
                        userLowId = pair.low,
                        userHighId = pair.high,
                        status = FriendshipStatus.ACTIVE,
                        createdAt = now,
                        removedAt = null,
                    ).also(friendshipRepository::save)
                }

            friendshipOutbox.appendFriendRequestAccepted(respondedRequest, friendship)
            friendship
        }

    fun rejectFriendRequest(
        receiverUserId: UserId,
        friendRequestId: FriendRequestId,
    ): FriendRequest =
        rejectFriendRequest(
            RejectFriendRequestCommand(
                receiverUserId = receiverUserId,
                friendRequestId = friendRequestId,
            ),
        )

    fun rejectFriendRequest(command: RejectFriendRequestCommand): FriendRequest =
        transactionRunner.runInTransaction {
            val request =
                friendRequestRepository.findById(command.friendRequestId)
                    ?: throw FriendRequestNotFoundException()

            if (request.receiverUserId != command.receiverUserId) {
                throw FriendshipNotAllowedException("Only the receiver can reject this friend request")
            }

            if (request.status != FriendRequestStatus.PENDING) {
                throw FriendshipStateException("Friend request is not pending")
            }

            val now = clock.now()
            val rejected =
                request.copy(
                    status = FriendRequestStatus.REJECTED,
                    respondedAt = now,
                )
            friendRequestRepository.update(rejected)
            friendshipOutbox.appendFriendRequestRejected(rejected)
            rejected
        }

    fun cancelFriendRequest(
        senderUserId: UserId,
        friendRequestId: FriendRequestId,
    ): FriendRequest =
        cancelFriendRequest(
            CancelFriendRequestCommand(
                senderUserId = senderUserId,
                friendRequestId = friendRequestId,
            ),
        )

    fun cancelFriendRequest(command: CancelFriendRequestCommand): FriendRequest =
        transactionRunner.runInTransaction {
            val request =
                friendRequestRepository.findById(command.friendRequestId)
                    ?: throw FriendRequestNotFoundException()

            if (request.senderUserId != command.senderUserId) {
                throw FriendshipNotAllowedException("Only the sender can cancel this friend request")
            }

            if (request.status != FriendRequestStatus.PENDING) {
                throw FriendshipStateException("Friend request is not pending")
            }

            val now = clock.now()
            val canceled =
                request.copy(
                    status = FriendRequestStatus.CANCELED,
                    canceledAt = now,
                )
            friendRequestRepository.update(canceled)
            friendshipOutbox.appendFriendRequestCanceled(canceled)
            canceled
        }

    fun removeFriend(
        actorUserId: UserId,
        friendUserId: UserId,
    ) {
        removeFriend(
            RemoveFriendCommand(
                actorUserId = actorUserId,
                friendUserId = friendUserId,
            ),
        )
    }

    fun removeFriend(command: RemoveFriendCommand) {
        transactionRunner.runInTransaction {
            ensureNotSelf(command.actorUserId, command.friendUserId)

            val friendship =
                friendshipRepository.findActiveBetween(command.actorUserId, command.friendUserId)
                    ?: throw FriendshipNotFoundException()

            val now = clock.now()
            if (!friendshipRepository.markRemoved(friendship.id, now)) {
                throw FriendshipNotFoundException()
            }

            val removed = friendship.copy(status = FriendshipStatus.REMOVED, removedAt = now)
            friendshipOutbox.appendFriendshipRemoved(removed)
        }
    }

    fun blockUser(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): BlockRelation =
        blockUser(
            BlockUserCommand(
                blockerUserId = blockerUserId,
                blockedUserId = blockedUserId,
            ),
        )

    fun blockUser(command: BlockUserCommand): BlockRelation =
        transactionRunner.runInTransaction {
            ensureNotSelf(command.blockerUserId, command.blockedUserId)

            blockRepository.find(command.blockerUserId, command.blockedUserId)?.let { return@runInTransaction it }

            val now = clock.now()
            friendRequestRepository.cancelPendingBetween(command.blockerUserId, command.blockedUserId, now)

            friendshipRepository.findActiveBetween(command.blockerUserId, command.blockedUserId)?.let { friendship ->
                if (friendshipRepository.markRemoved(friendship.id, now)) {
                    friendshipOutbox.appendFriendshipRemoved(
                        friendship.copy(status = FriendshipStatus.REMOVED, removedAt = now),
                    )
                }
            }

            val block =
                BlockRelation(
                    blockerUserId = command.blockerUserId,
                    blockedUserId = command.blockedUserId,
                    createdAt = now,
                )
            blockRepository.save(block)
            friendshipOutbox.appendUserBlocked(block)
            block
        }

    fun unblockUser(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ) {
        unblockUser(
            UnblockUserCommand(
                blockerUserId = blockerUserId,
                blockedUserId = blockedUserId,
            ),
        )
    }

    fun unblockUser(command: UnblockUserCommand) {
        transactionRunner.runInTransaction {
            ensureNotSelf(command.blockerUserId, command.blockedUserId)

            if (!blockRepository.delete(command.blockerUserId, command.blockedUserId)) {
                throw BlockRelationNotFoundException()
            }

            friendshipOutbox.appendUserUnblocked(command.blockerUserId, command.blockedUserId)
        }
    }

    fun getFriendshipSummary(
        viewerUserId: UserId,
        targetUserId: UserId,
    ): FriendshipSummary =
        transactionRunner.runInTransaction {
            buildSummary(viewerUserId, targetUserId)
        }

    fun batchGetFriendshipSummaries(
        viewerUserId: UserId,
        targetUserIds: List<UserId>,
    ): List<FriendshipSummary> =
        transactionRunner.runInTransaction {
            targetUserIds.map { buildSummary(viewerUserId, it) }
        }

    fun listFriends(
        targetUserId: UserId,
        page: ValidatedPageRequest<CreatedAtIdCursor>,
    ): PageSlice<UserId> =
        transactionRunner.runInTransaction {
            val fetchLimit = page.limit + 1
            val friendships =
                friendshipRepository.listActiveFriends(
                    userId = targetUserId,
                    limit = fetchLimit,
                    cursor = page.cursor,
                )

            val friendUserIds =
                friendships.map { friendship ->
                    friendship.otherUserId(targetUserId)
                }

            val nextCursor =
                if (friendships.size > page.limit) {
                    val last = friendships[page.limit - 1]
                    CreatedAtIdCursor(createdAt = last.createdAt, id = last.id.value)
                } else {
                    null
                }

            buildPageSlice(
                items = friendUserIds,
                limit = page.limit,
                nextCursor = nextCursor,
            )
        }

    fun listIncomingFriendRequests(
        receiverUserId: UserId,
        page: ValidatedPageRequest<CreatedAtIdCursor>,
    ): PageSlice<FriendRequest> =
        transactionRunner.runInTransaction {
            paginateFriendRequests(page) {
                friendRequestRepository.listIncoming(receiverUserId, it, page.cursor)
            }
        }

    fun listOutgoingFriendRequests(
        senderUserId: UserId,
        page: ValidatedPageRequest<CreatedAtIdCursor>,
    ): PageSlice<FriendRequest> =
        transactionRunner.runInTransaction {
            paginateFriendRequests(page) {
                friendRequestRepository.listOutgoing(senderUserId, it, page.cursor)
            }
        }

    fun checkCanViewUserPosts(
        viewerUserId: UserId,
        ownerUserId: UserId,
    ): PostVisibility =
        transactionRunner.runInTransaction {
            resolvePostVisibility(viewerUserId, ownerUserId)
        }

    private fun paginateFriendRequests(
        page: ValidatedPageRequest<CreatedAtIdCursor>,
        fetch: (limit: Int) -> List<FriendRequest>,
    ): PageSlice<FriendRequest> {
        val fetchLimit = page.limit + 1
        val requests = fetch(fetchLimit)
        val nextCursor =
            if (requests.size > page.limit) {
                val last = requests[page.limit - 1]
                CreatedAtIdCursor(createdAt = last.createdAt, id = last.id.value)
            } else {
                null
            }

        return buildPageSlice(
            items = requests,
            limit = page.limit,
            nextCursor = nextCursor,
        )
    }

    private fun buildSummary(
        viewerUserId: UserId,
        targetUserId: UserId,
    ): FriendshipSummary {
        if (viewerUserId == targetUserId) {
            return FriendshipSummary(
                targetUserId = targetUserId,
                relationStatus = FriendshipRelationStatus.FRIENDS,
                canViewPosts = true,
                canSendRequest = false,
            )
        }

        val relationStatus = resolveRelationStatus(viewerUserId, targetUserId)
        return FriendshipSummary(
            targetUserId = targetUserId,
            relationStatus = relationStatus,
            canViewPosts = relationStatus == FriendshipRelationStatus.FRIENDS,
            canSendRequest = relationStatus == FriendshipRelationStatus.NONE,
        )
    }

    private fun resolveRelationStatus(
        viewerUserId: UserId,
        targetUserId: UserId,
    ): FriendshipRelationStatus {
        if (blockRepository.find(viewerUserId, targetUserId) != null) {
            return FriendshipRelationStatus.BLOCKED_BY_ME
        }
        if (blockRepository.find(targetUserId, viewerUserId) != null) {
            return FriendshipRelationStatus.BLOCKED_ME
        }
        if (friendshipRepository.findActiveBetween(viewerUserId, targetUserId) != null) {
            return FriendshipRelationStatus.FRIENDS
        }

        val pending = friendRequestRepository.findPendingBetween(viewerUserId, targetUserId)
        if (pending != null) {
            return if (pending.senderUserId == viewerUserId) {
                FriendshipRelationStatus.REQUEST_SENT
            } else {
                FriendshipRelationStatus.REQUEST_RECEIVED
            }
        }

        return FriendshipRelationStatus.NONE
    }

    private fun resolvePostVisibility(
        viewerUserId: UserId,
        ownerUserId: UserId,
    ): PostVisibility {
        if (viewerUserId == ownerUserId) {
            return PostVisibility(
                canView = true,
                relationStatus = FriendshipRelationStatus.FRIENDS,
            )
        }

        val relationStatus = resolveRelationStatus(viewerUserId, ownerUserId)
        return PostVisibility(
            canView = relationStatus == FriendshipRelationStatus.FRIENDS,
            relationStatus = relationStatus,
        )
    }

    private fun ensureNotSelf(
        firstUserId: UserId,
        secondUserId: UserId,
    ) {
        if (firstUserId == secondUserId) {
            throw InvalidFriendshipInputException("Cannot perform this action on yourself")
        }
    }

    private fun ensureNotBlocked(
        firstUserId: UserId,
        secondUserId: UserId,
    ) {
        if (blockRepository.find(firstUserId, secondUserId) != null) {
            throw FriendshipNotAllowedException("User is blocked")
        }
        if (blockRepository.find(secondUserId, firstUserId) != null) {
            throw FriendshipNotAllowedException("User has blocked you")
        }
    }
}

data class PostVisibility(
    val canView: Boolean,
    val relationStatus: FriendshipRelationStatus,
)
