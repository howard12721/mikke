package jp.xhw.mikke.services.friendship.application.command

import jp.xhw.mikke.services.friendship.model.FriendRequestId
import jp.xhw.mikke.services.friendship.model.UserId

data class SendFriendRequestCommand(
    val senderUserId: UserId,
    val receiverUserId: UserId,
)

data class AcceptFriendRequestCommand(
    val receiverUserId: UserId,
    val friendRequestId: FriendRequestId,
)

data class RejectFriendRequestCommand(
    val receiverUserId: UserId,
    val friendRequestId: FriendRequestId,
)

data class CancelFriendRequestCommand(
    val senderUserId: UserId,
    val friendRequestId: FriendRequestId,
)

data class RemoveFriendCommand(
    val actorUserId: UserId,
    val friendUserId: UserId,
)

data class BlockUserCommand(
    val blockerUserId: UserId,
    val blockedUserId: UserId,
)

data class UnblockUserCommand(
    val blockerUserId: UserId,
    val blockedUserId: UserId,
)
