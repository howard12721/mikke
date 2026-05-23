package jp.xhw.mikke.services.friendship

import io.grpc.Status
import jp.xhw.mikke.services.friendship.application.exception.BlockRelationNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.DuplicateFriendRequestException
import jp.xhw.mikke.services.friendship.application.exception.FriendRequestNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipApplicationException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipNotAllowedException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipNotFoundException
import jp.xhw.mikke.services.friendship.application.exception.FriendshipStateException
import jp.xhw.mikke.services.friendship.application.exception.InvalidFriendshipInputException

fun FriendshipApplicationException.toGrpcStatus(): Status =
    when (this) {
        is InvalidFriendshipInputException -> Status.INVALID_ARGUMENT.withDescription(message)
        is DuplicateFriendRequestException -> Status.ALREADY_EXISTS.withDescription(message)
        is FriendRequestNotFoundException -> Status.NOT_FOUND.withDescription(message)
        is FriendshipNotFoundException -> Status.NOT_FOUND.withDescription(message)
        is BlockRelationNotFoundException -> Status.NOT_FOUND.withDescription(message)
        is FriendshipStateException -> Status.FAILED_PRECONDITION.withDescription(message)
        is FriendshipNotAllowedException -> Status.PERMISSION_DENIED.withDescription(message)
    }
