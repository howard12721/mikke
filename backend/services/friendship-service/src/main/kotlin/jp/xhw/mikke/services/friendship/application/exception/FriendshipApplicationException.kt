package jp.xhw.mikke.services.friendship.application.exception

sealed class FriendshipApplicationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidFriendshipInputException(
    message: String,
    cause: Throwable? = null,
) : FriendshipApplicationException(message, cause)

class DuplicateFriendRequestException(
    message: String = "A pending friend request already exists between these users",
    cause: Throwable? = null,
) : FriendshipApplicationException(message, cause)

class FriendRequestNotFoundException(
    message: String = "Friend request not found",
    cause: Throwable? = null,
) : FriendshipApplicationException(message, cause)

class FriendshipNotFoundException(
    message: String = "Friendship not found",
    cause: Throwable? = null,
) : FriendshipApplicationException(message, cause)

class BlockRelationNotFoundException(
    message: String = "Block relation not found",
    cause: Throwable? = null,
) : FriendshipApplicationException(message, cause)

class FriendshipStateException(
    message: String,
    cause: Throwable? = null,
) : FriendshipApplicationException(message, cause)

class FriendshipNotAllowedException(
    message: String,
    cause: Throwable? = null,
) : FriendshipApplicationException(message, cause)
