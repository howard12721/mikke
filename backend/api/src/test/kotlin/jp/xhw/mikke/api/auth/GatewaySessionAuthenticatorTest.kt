package jp.xhw.mikke.api.auth.application

import jp.xhw.mikke.api.auth.infrastructure.RecordingGatewaySessionReader
import jp.xhw.mikke.platform.auth.session.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class GatewaySessionAuthenticatorTest {
    private val userId = "550e8400-e29b-41d4-a716-446655440000"
    private val sessionId =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(SessionId.BYTE_LENGTH) { 3 })
    private val sessionHash = SessionId.hash(sessionId)
    private val issuedAt = Instant.parse("2026-04-23T00:00:00Z")

    @Test
    fun `authenticates valid session`() {
        val reader = RecordingGatewaySessionReader()
        reader.putSession(sessionHash, userId, version = 1, issuedAt = issuedAt)
        val authenticator =
            GatewaySessionAuthenticator(
                sessionReader = reader,
                clock = fixedClock(issuedAt + 1.days),
            )

        val result = authenticator.authenticate(sessionId)

        assertInstanceOf(GatewayAuthenticationResult.Authenticated::class.java, result)
        val authenticated = result as GatewayAuthenticationResult.Authenticated
        assertEquals(userId, authenticated.actor.userId)
        assertEquals(sessionHash, authenticated.actor.sessionHash)
    }

    @Test
    fun `rejects version mismatch fail closed`() {
        val reader = RecordingGatewaySessionReader()
        reader.putSession(sessionHash, userId, version = 1, issuedAt = issuedAt)
        reader.versions[userId] = 2
        val authenticator =
            GatewaySessionAuthenticator(
                sessionReader = reader,
                clock = fixedClock(issuedAt + 1.days),
            )

        val result = authenticator.authenticate(sessionId)

        assertEquals(
            GatewayAuthenticationResult.Failed(GatewaySessionAuthFailure.VersionMismatch),
            result,
        )
    }

    @Test
    fun `rejects redis read failure fail closed`() {
        val reader =
            RecordingGatewaySessionReader().apply {
                failOnSessionRead = true
            }
        val authenticator = GatewaySessionAuthenticator(sessionReader = reader)

        val result = authenticator.authenticate(sessionId)

        assertEquals(
            GatewayAuthenticationResult.Failed(GatewaySessionAuthFailure.RedisFailure),
            result,
        )
    }

    private fun fixedClock(now: Instant): Clock =
        object : Clock {
            override fun now(): Instant = now
        }
}

class GatewaySessionTouchDebounceTrackerTest {
    @Test
    fun `atomically marks only one due touch per debounce window`() {
        var now = 0L
        val tracker =
            GatewaySessionTouchDebounceTracker(
                debounceWindowMs = 10.minutesMs,
                nowMillis = { now },
            )

        assertEquals(true, tracker.markScheduledIfDue("hash-1"))
        assertEquals(false, tracker.markScheduledIfDue("hash-1"))

        now += 11.minutesMs

        assertEquals(true, tracker.markScheduledIfDue("hash-1"))
    }
}

private val Int.minutesMs: Long get() = this * 60_000L
