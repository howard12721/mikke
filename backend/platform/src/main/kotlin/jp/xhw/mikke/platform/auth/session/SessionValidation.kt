package jp.xhw.mikke.platform.auth.session

import kotlin.time.Instant

enum class SessionValidationFailure {
    MalformedSessionId,
    MissingSession,
    MalformedPayload,
    ExpiredIdle,
    ExpiredAbsolute,
    MissingUserSessionVersion,
    VersionMismatch,
}

data class AuthenticatedActor(
    val userId: String,
    val sessionHash: String,
)

object SessionValidation {
    fun validateRecord(
        record: SessionRecord,
        projectedUserSessionVersion: Int?,
        now: Instant,
    ): SessionValidationFailure? {
        if (now >= record.absoluteExpiresAt) {
            return SessionValidationFailure.ExpiredAbsolute
        }
        if (now >= record.idleExpiresAt) {
            return SessionValidationFailure.ExpiredIdle
        }
        if (projectedUserSessionVersion == null) {
            return SessionValidationFailure.MissingUserSessionVersion
        }
        if (projectedUserSessionVersion != record.userSessionVersion) {
            return SessionValidationFailure.VersionMismatch
        }
        return null
    }

    fun shouldTouchSession(
        record: SessionRecord,
        now: Instant,
    ): Boolean = (now - record.lastTouchedAt) >= SessionLifetime.touchThreshold
}
