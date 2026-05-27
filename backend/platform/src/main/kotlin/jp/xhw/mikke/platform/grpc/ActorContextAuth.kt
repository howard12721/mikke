package jp.xhw.mikke.platform.grpc

import io.grpc.Status
import io.grpc.StatusException
import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import kotlin.uuid.Uuid

fun ActorContext.requireUserUuid(): Uuid {
    val trimmed = userId.trim()
    if (trimmed.isEmpty()) {
        throw StatusException(Status.INVALID_ARGUMENT.withDescription("actor.user_id is required"))
    }

    return try {
        parseGrpcUuid(trimmed, fieldName = "actor.user_id")
    } catch (_: ValidationException) {
        throw StatusException(Status.INVALID_ARGUMENT.withDescription("Invalid actor.user_id"))
    }
}
