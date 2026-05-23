package jp.xhw.mikke.services.friendship.model

import kotlin.uuid.Uuid

@JvmInline
value class FriendshipId(
    val value: Uuid,
)
