package jp.xhw.mikke.services.identity.application.input

import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.platform.auth.session.SessionId
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.platform.uuid.parseGrpcUuidOrNull
import jp.xhw.mikke.services.identity.application.exception.InvalidSessionHashException
import jp.xhw.mikke.services.identity.model.AvatarMediaId
import jp.xhw.mikke.services.identity.model.UserId

fun parseUserId(raw: String): UserId = UserId(parseGrpcUuid(raw, fieldName = "user_id"))

fun parseActorUserId(actor: ActorContext): UserId = parseUserId(actor.userId.requireField("actor.user_id"))

fun parseSessionHash(raw: String): String {
    val sessionHash = raw.requireField("session_hash")
    if (!SessionId.isValidSessionHash(sessionHash)) {
        throw InvalidSessionHashException()
    }
    return sessionHash
}

fun parseAvatarMediaId(raw: String): AvatarMediaId = AvatarMediaId(parseGrpcUuid(raw, fieldName = "avatar_media_id"))

fun parseAvatarMediaIdOrNull(raw: String?): AvatarMediaId? = parseGrpcUuidOrNull(raw, fieldName = "avatar_media_id")?.let(::AvatarMediaId)

private fun String.requireField(fieldName: String): String =
    trim().takeIf { it.isNotEmpty() }
        ?: throw jp.xhw.mikke.services.identity.application.exception
            .InvalidIdentityInputException("$fieldName is required")
