package jp.xhw.mikke.platform.auth.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

data class SessionRecord(
    val userId: String,
    val userSessionVersion: Int,
    val issuedAt: Instant,
    val lastTouchedAt: Instant,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
)

@Serializable
private data class SessionRecordPayload(
    val schemaVersion: Int,
    val userId: String,
    val userSessionVersion: Int,
    val issuedAt: Long,
    val lastTouchedAt: Long,
    val idleExpiresAt: Long,
    val absoluteExpiresAt: Long,
)

class SessionRecordParseException(
    message: String,
) : IllegalArgumentException(message)

object SessionRecordCodec {
    const val CURRENT_SCHEMA_VERSION = 1

    private val json =
        Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }

    fun serialize(
        record: SessionRecord,
        schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    ): String {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported schema version: $schemaVersion"
        }

        val payload =
            SessionRecordPayload(
                schemaVersion = schemaVersion,
                userId = record.userId,
                userSessionVersion = record.userSessionVersion,
                issuedAt = record.issuedAt.toEpochMilliseconds(),
                lastTouchedAt = record.lastTouchedAt.toEpochMilliseconds(),
                idleExpiresAt = record.idleExpiresAt.toEpochMilliseconds(),
                absoluteExpiresAt = record.absoluteExpiresAt.toEpochMilliseconds(),
            )

        return json.encodeToString(SessionRecordPayload.serializer(), payload)
    }

    fun deserialize(raw: String): SessionRecord {
        val payload =
            try {
                json.decodeFromString(SessionRecordPayload.serializer(), raw)
            } catch (_: Exception) {
                throw SessionRecordParseException("Malformed session record JSON")
            }

        if (payload.schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw SessionRecordParseException("Unsupported schema version: ${payload.schemaVersion}")
        }

        return payload.toRecord()
    }

    fun createNew(
        userId: String,
        userSessionVersion: Int,
        issuedAt: Instant,
    ): SessionRecord =
        SessionRecord(
            userId = userId,
            userSessionVersion = userSessionVersion,
            issuedAt = issuedAt,
            lastTouchedAt = issuedAt,
            idleExpiresAt = issuedAt + SessionLifetime.idleLifetime,
            absoluteExpiresAt = issuedAt + SessionLifetime.absoluteLifetime,
        )

    fun touch(
        record: SessionRecord,
        touchedAt: Instant,
    ): SessionRecord =
        record.copy(
            lastTouchedAt = touchedAt,
            idleExpiresAt = touchedAt + SessionLifetime.idleLifetime,
        )

    fun redisTtlSeconds(
        record: SessionRecord,
        now: Instant,
    ): Long {
        val remaining = record.absoluteExpiresAt - now
        return remaining.inWholeSeconds.coerceAtLeast(1)
    }
}

private fun SessionRecordPayload.toRecord(): SessionRecord {
    if (userId.isBlank()) {
        throw SessionRecordParseException("userId must not be blank")
    }
    if (userSessionVersion < 0) {
        throw SessionRecordParseException("userSessionVersion must not be negative")
    }
    if (issuedAt < 0 || lastTouchedAt < 0 || idleExpiresAt < 0 || absoluteExpiresAt < 0) {
        throw SessionRecordParseException("Timestamps must not be negative")
    }
    if (lastTouchedAt < issuedAt) {
        throw SessionRecordParseException("lastTouchedAt must not be before issuedAt")
    }
    if (idleExpiresAt < lastTouchedAt) {
        throw SessionRecordParseException("idleExpiresAt must not be before lastTouchedAt")
    }
    if (absoluteExpiresAt < issuedAt) {
        throw SessionRecordParseException("absoluteExpiresAt must not be before issuedAt")
    }

    return SessionRecord(
        userId = userId,
        userSessionVersion = userSessionVersion,
        issuedAt = Instant.fromEpochMilliseconds(issuedAt),
        lastTouchedAt = Instant.fromEpochMilliseconds(lastTouchedAt),
        idleExpiresAt = Instant.fromEpochMilliseconds(idleExpiresAt),
        absoluteExpiresAt = Instant.fromEpochMilliseconds(absoluteExpiresAt),
    )
}
