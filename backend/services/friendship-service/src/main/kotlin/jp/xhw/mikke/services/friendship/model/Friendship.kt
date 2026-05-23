package jp.xhw.mikke.services.friendship.model

import kotlin.time.Instant

data class Friendship(
    val id: FriendshipId,
    val userLowId: UserId,
    val userHighId: UserId,
    val status: FriendshipStatus,
    val createdAt: Instant,
    val removedAt: Instant?,
) {
    fun otherUserId(userId: UserId): UserId =
        when (userId) {
            userLowId -> userHighId
            userHighId -> userLowId
            else -> throw IllegalArgumentException("user is not part of this friendship")
        }
}

data class NormalizedUserPair(
    val low: UserId,
    val high: UserId,
) {
    init {
        require(low.value < high.value) { "user ids must be normalized" }
    }

    companion object {
        fun of(
            first: UserId,
            second: UserId,
        ): NormalizedUserPair {
            require(first != second) { "cannot form a pair with the same user" }
            return if (first.value < second.value) {
                NormalizedUserPair(first, second)
            } else {
                NormalizedUserPair(second, first)
            }
        }
    }
}
