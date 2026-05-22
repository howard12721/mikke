package jp.xhw.mikke.services.post.model

import kotlin.uuid.Uuid

@JvmInline
value class UserId(
    val value: Uuid,
)

@JvmInline
value class PostId(
    val value: Uuid,
)

@JvmInline
value class MediaId(
    val value: Uuid,
)
