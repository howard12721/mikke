package jp.xhw.mikke.platform.uuid

import jp.xhw.mikke.platform.grpc.ValidationException
import kotlin.uuid.Uuid

fun formatGrpcUuid(value: Uuid): String = value.toString()

fun parseGrpcUuid(
    raw: String,
    fieldName: String = "id",
): Uuid {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        throw ValidationException("$fieldName must not be blank")
    }

    return runCatching { Uuid.parse(trimmed) }
        .getOrElse { throw ValidationException("$fieldName must be a valid UUID") }
}

fun parseGrpcUuidOrNull(
    raw: String?,
    fieldName: String = "id",
): Uuid? {
    if (raw.isNullOrBlank()) {
        return null
    }
    return parseGrpcUuid(raw, fieldName)
}
