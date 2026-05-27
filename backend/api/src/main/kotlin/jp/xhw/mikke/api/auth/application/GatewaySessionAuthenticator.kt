package jp.xhw.mikke.api.auth.application

import jp.xhw.mikke.platform.auth.session.AuthenticatedActor
import jp.xhw.mikke.platform.auth.session.SessionId
import jp.xhw.mikke.platform.auth.session.SessionValidation
import jp.xhw.mikke.platform.auth.session.SessionValidationFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

sealed interface GatewayAuthenticationResult {
    data class Authenticated(
        val actor: AuthenticatedActor,
    ) : GatewayAuthenticationResult

    data class Failed(
        val reason: GatewaySessionAuthFailure,
    ) : GatewayAuthenticationResult
}

class GatewaySessionAuthenticator(
    private val sessionReader: GatewaySessionReader,
    private val clock: Clock = Clock.System,
) {
    fun authenticate(sessionId: String): GatewayAuthenticationResult {
        if (!SessionId.isValidSessionId(sessionId)) {
            return GatewayAuthenticationResult.Failed(GatewaySessionAuthFailure.MalformedSessionId)
        }

        val sessionHash = SessionId.hash(sessionId)
        val record =
            try {
                sessionReader.findSession(sessionHash)
            } catch (_: Exception) {
                return GatewayAuthenticationResult.Failed(GatewaySessionAuthFailure.RedisFailure)
            } ?: return GatewayAuthenticationResult.Failed(GatewaySessionAuthFailure.MissingSession)

        val projectedVersion =
            try {
                sessionReader.findUserSessionVersion(record.userId)
            } catch (_: Exception) {
                return GatewayAuthenticationResult.Failed(GatewaySessionAuthFailure.RedisFailure)
            }

        val validationFailure =
            SessionValidation.validateRecord(
                record = record,
                projectedUserSessionVersion = projectedVersion,
                now = clock.now(),
            )
        if (validationFailure != null) {
            return GatewayAuthenticationResult.Failed(validationFailure.toGatewayFailure())
        }

        return GatewayAuthenticationResult.Authenticated(
            actor =
                AuthenticatedActor(
                    userId = record.userId,
                    sessionHash = sessionHash,
                ),
        )
    }
}

private fun SessionValidationFailure.toGatewayFailure(): GatewaySessionAuthFailure =
    when (this) {
        SessionValidationFailure.MalformedSessionId -> GatewaySessionAuthFailure.MalformedSessionId
        SessionValidationFailure.MissingSession -> GatewaySessionAuthFailure.MissingSession
        SessionValidationFailure.MalformedPayload -> GatewaySessionAuthFailure.MalformedPayload
        SessionValidationFailure.ExpiredIdle -> GatewaySessionAuthFailure.ExpiredIdle
        SessionValidationFailure.ExpiredAbsolute -> GatewaySessionAuthFailure.ExpiredAbsolute
        SessionValidationFailure.MissingUserSessionVersion -> GatewaySessionAuthFailure.MissingUserSessionVersion
        SessionValidationFailure.VersionMismatch -> GatewaySessionAuthFailure.VersionMismatch
    }

class GatewaySessionTouchDebounceTracker(
    private val debounceWindowMs: Long = SessionTouchDebounceWindowMs,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lastScheduledAtBySessionHash = ConcurrentHashMap<String, Long>()

    fun markScheduledIfDue(sessionHash: String): Boolean {
        val now = nowMillis()
        var shouldSchedule = false
        lastScheduledAtBySessionHash.compute(sessionHash) { _, lastScheduledAt ->
            shouldSchedule = lastScheduledAt == null || now - lastScheduledAt >= debounceWindowMs
            if (shouldSchedule) now else lastScheduledAt
        }
        return shouldSchedule
    }
}

private val SessionTouchDebounceWindowMs: Long =
    jp.xhw.mikke.platform.auth.session.SessionLifetime.gatewayTouchDebounce.inWholeMilliseconds

class GatewaySessionTouchScheduler(
    private val scope: CoroutineScope,
    private val sessionReader: GatewaySessionReader,
    private val identitySessionGateway: IdentitySessionGateway,
    private val debounceTracker: GatewaySessionTouchDebounceTracker = GatewaySessionTouchDebounceTracker(),
    private val clock: Clock = Clock.System,
) {
    fun scheduleTouchIfNeeded(sessionHash: String) {
        val record =
            try {
                sessionReader.findSession(sessionHash)
            } catch (_: Exception) {
                return
            } ?: return

        if (!SessionValidation.shouldTouchSession(record, clock.now())) {
            return
        }

        if (!debounceTracker.markScheduledIfDue(sessionHash)) {
            return
        }

        scope.launch {
            runCatching { identitySessionGateway.touchSession(sessionHash) }
        }
    }
}
