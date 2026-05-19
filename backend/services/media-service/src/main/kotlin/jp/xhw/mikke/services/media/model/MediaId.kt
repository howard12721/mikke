package jp.xhw.mikke.services.media.model

import kotlin.uuid.Uuid

@JvmInline
value class MediaId(
    val value: Uuid,
)

@JvmInline
value class MediaVariantId(
    val value: Uuid,
)

@JvmInline
value class UploaderUserId(
    val value: Uuid,
)
