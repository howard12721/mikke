package jp.xhw.mikke.services.friendship.model

import kotlin.time.Instant

data class BlockRelation(
    val blockerUserId: UserId,
    val blockedUserId: UserId,
    val createdAt: Instant,
)
