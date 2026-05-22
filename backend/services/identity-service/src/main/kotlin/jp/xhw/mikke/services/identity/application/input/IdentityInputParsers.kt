package jp.xhw.mikke.services.identity.application.input

import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.platform.uuid.parseGrpcUuidOrNull
import jp.xhw.mikke.services.identity.model.AvatarMediaId
import jp.xhw.mikke.services.identity.model.UserId

fun parseUserId(raw: String): UserId = UserId(parseGrpcUuid(raw, fieldName = "user_id"))

fun parseAvatarMediaId(raw: String): AvatarMediaId = AvatarMediaId(parseGrpcUuid(raw, fieldName = "avatar_media_id"))

fun parseAvatarMediaIdOrNull(raw: String?): AvatarMediaId? = parseGrpcUuidOrNull(raw, fieldName = "avatar_media_id")?.let(::AvatarMediaId)
