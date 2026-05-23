package jp.xhw.mikke.services.friendship.model

import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import kotlin.uuid.Uuid

@JvmInline
value class FriendRequestId(
    val value: Uuid,
)

fun parseFriendRequestId(raw: String): FriendRequestId = FriendRequestId(parseGrpcUuid(raw, fieldName = "friend_request_id"))
