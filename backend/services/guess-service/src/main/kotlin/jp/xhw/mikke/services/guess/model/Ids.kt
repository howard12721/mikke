package jp.xhw.mikke.services.guess.model

import kotlin.uuid.Uuid

@JvmInline
value class GuessId(
    val value: Uuid,
)

@JvmInline
value class PostId(
    val value: Uuid,
)

@JvmInline
value class UserId(
    val value: Uuid,
)
