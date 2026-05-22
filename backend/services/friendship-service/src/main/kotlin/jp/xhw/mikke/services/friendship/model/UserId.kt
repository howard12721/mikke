package jp.xhw.mikke.services.friendship.model

import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import kotlin.uuid.Uuid

@JvmInline
value class UserId(
    val value: Uuid,
)

fun parseUserId(raw: String): UserId = UserId(parseGrpcUuid(raw, fieldName = "user_id"))
