package jp.xhw.mikke.services.friendship.model

data class FriendshipSummary(
    val targetUserId: UserId,
    val relationStatus: FriendshipRelationStatus,
    val canViewPosts: Boolean,
    val canSendRequest: Boolean,
)
