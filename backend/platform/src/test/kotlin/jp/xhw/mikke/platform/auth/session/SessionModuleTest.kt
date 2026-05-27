package jp.xhw.mikke.platform.auth.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.security.SecureRandom
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SessionIdTest {
    @Test
    fun `generated session id is 43 character base64url without padding`() {
        val sessionId = SessionId.generate()

        assertEquals(SessionId.ENCODED_LENGTH, sessionId.length)
        assertTrue(SessionId.isValidSessionId(sessionId))
        assertFalse(sessionId.contains('='))
    }

    @Test
    fun `generated session ids are unique across many draws`() {
        val ids = (1..100).map { SessionId.generate() }.toSet()

        assertEquals(100, ids.size)
    }

    @Test
    fun `hash is lowercase hex sha256 of session id`() {
        val sessionId = SessionId.generate()
        val hash = SessionId.hash(sessionId)

        assertEquals(SessionId.HASH_HEX_LENGTH, hash.length)
        assertTrue(SessionId.isValidSessionHash(hash))
        assertEquals(hash, hash.lowercase())
        assertEquals(hash, SessionId.hash(sessionId))
    }

    @Test
    fun `hash rejects invalid session id format`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionId.hash("not-a-valid-session-id")
        }
    }

    @Test
    fun `rejects session ids with wrong length or charset`() {
        val validSessionId = SessionId.generate()

        assertFalse(SessionId.isValidSessionId(""))
        assertFalse(SessionId.isValidSessionId(validSessionId + "x"))
        assertFalse(SessionId.isValidSessionId(validSessionId.dropLast(1)))
        assertFalse(SessionId.isValidSessionId("+" + validSessionId.drop(1)))
        assertFalse(SessionId.isValidSessionId(validSessionId.dropLast(1) + "="))
    }

    @Test
    fun `deterministic secure random produces predictable session id`() {
        val bytes = ByteArray(SessionId.BYTE_LENGTH) { index -> index.toByte() }
        val secureRandom =
            object : SecureRandom() {
                override fun nextBytes(output: ByteArray) {
                    bytes.copyInto(output)
                }
            }

        val sessionId = SessionId.generate(secureRandom)
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        assertEquals(expected, sessionId)
    }
}

class SessionKeysTest {
    @Test
    fun `builds redis keys from session hash and user id`() {
        val sessionId = SessionId.generate()
        val sessionHash = SessionId.hash(sessionId)
        val userId = "550e8400-e29b-41d4-a716-446655440000"

        assertEquals("auth:session:$sessionHash", SessionKeys.sessionKey(sessionHash))
        assertEquals("auth:user-session-version:$userId", SessionKeys.userSessionVersionKey(userId))
    }

    @Test
    fun `rejects invalid session hash for session key`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionKeys.sessionKey("not-a-hash")
        }
    }

    @Test
    fun `rejects blank user id for version key`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionKeys.userSessionVersionKey("  ")
        }
    }
}

class SessionAuthorizationTest {
    private val sessionId = SessionId.generate()

    @Test
    fun `parses valid session authorization header`() {
        val parsed = SessionAuthorization.parse("Session $sessionId")

        assertInstanceOf<ParsedSessionAuthorization.Valid>(parsed)
        assertEquals(sessionId, parsed.sessionId)
    }

    @Test
    fun `parses session scheme case insensitively`() {
        val parsed = SessionAuthorization.parse("session $sessionId")

        assertInstanceOf<ParsedSessionAuthorization.Valid>(parsed)
    }

    @Test
    fun `missing header is reported as missing`() {
        assertEquals(ParsedSessionAuthorization.Missing, SessionAuthorization.parse(null))
        assertEquals(ParsedSessionAuthorization.Missing, SessionAuthorization.parse(""))
        assertEquals(ParsedSessionAuthorization.Missing, SessionAuthorization.parse("   "))
    }

    @Test
    fun `malformed headers are rejected`() {
        assertEquals(ParsedSessionAuthorization.Malformed, SessionAuthorization.parse("Bearer token"))
        assertEquals(ParsedSessionAuthorization.Malformed, SessionAuthorization.parse("Session"))
        assertEquals(ParsedSessionAuthorization.Malformed, SessionAuthorization.parse("Session $sessionId extra"))
        assertEquals(ParsedSessionAuthorization.Malformed, SessionAuthorization.parse("Session not-valid"))
    }
}

class SessionRecordCodecTest {
    private val issuedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val userId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `createNew applies configured lifetimes`() {
        val record = SessionRecordCodec.createNew(userId = userId, userSessionVersion = 2, issuedAt = issuedAt)

        assertEquals(userId, record.userId)
        assertEquals(2, record.userSessionVersion)
        assertEquals(issuedAt, record.issuedAt)
        assertEquals(issuedAt, record.lastTouchedAt)
        assertEquals(issuedAt + SessionLifetime.idleLifetime, record.idleExpiresAt)
        assertEquals(issuedAt + SessionLifetime.absoluteLifetime, record.absoluteExpiresAt)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val record = SessionRecordCodec.createNew(userId = userId, userSessionVersion = 1, issuedAt = issuedAt)
        val json = SessionRecordCodec.serialize(record)

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"userId\":\"$userId\""))

        val restored = SessionRecordCodec.deserialize(json)

        assertEquals(record, restored)
    }

    @Test
    fun `touch extends idle expiry from touch time`() {
        val record = SessionRecordCodec.createNew(userId = userId, userSessionVersion = 1, issuedAt = issuedAt)
        val touchedAt = issuedAt + 25.hours
        val touched = SessionRecordCodec.touch(record, touchedAt)

        assertEquals(touchedAt, touched.lastTouchedAt)
        assertEquals(touchedAt + SessionLifetime.idleLifetime, touched.idleExpiresAt)
        assertEquals(record.absoluteExpiresAt, touched.absoluteExpiresAt)
    }

    @Test
    fun `redis ttl aligns with remaining absolute lifetime`() {
        val record = SessionRecordCodec.createNew(userId = userId, userSessionVersion = 1, issuedAt = issuedAt)
        val now = record.absoluteExpiresAt - 5.days

        assertEquals(5.days.inWholeSeconds, SessionRecordCodec.redisTtlSeconds(record, now))
    }

    @Test
    fun `rejects malformed json and unsupported schema version`() {
        assertThrows(SessionRecordParseException::class.java) {
            SessionRecordCodec.deserialize("{not-json")
        }

        val unsupportedSchema =
            """
            {
              "schemaVersion": 99,
              "userId": "$userId",
              "userSessionVersion": 1,
              "issuedAt": 1,
              "lastTouchedAt": 1,
              "idleExpiresAt": 2,
              "absoluteExpiresAt": 3
            }
            """.trimIndent()

        assertThrows(SessionRecordParseException::class.java) {
            SessionRecordCodec.deserialize(unsupportedSchema)
        }
    }

    @Test
    fun `rejects inconsistent timestamps in payload`() {
        val invalidLastTouched =
            """
            {
              "schemaVersion": 1,
              "userId": "$userId",
              "userSessionVersion": 1,
              "issuedAt": 10,
              "lastTouchedAt": 5,
              "idleExpiresAt": 20,
              "absoluteExpiresAt": 30
            }
            """.trimIndent()

        assertThrows(SessionRecordParseException::class.java) {
            SessionRecordCodec.deserialize(invalidLastTouched)
        }
    }
}

class SessionValidationTest {
    private val issuedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val userId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `valid record passes validation when version matches`() {
        val record = SessionRecordCodec.createNew(userId = userId, userSessionVersion = 3, issuedAt = issuedAt)
        val now = issuedAt + 1.days

        assertNull(SessionValidation.validateRecord(record, projectedUserSessionVersion = 3, now = now))
    }

    @Test
    fun `detects absolute and idle expiry`() {
        val record = SessionRecordCodec.createNew(userId = userId, userSessionVersion = 1, issuedAt = issuedAt)

        assertEquals(
            SessionValidationFailure.ExpiredAbsolute,
            SessionValidation.validateRecord(record, projectedUserSessionVersion = 1, now = record.absoluteExpiresAt),
        )
        assertEquals(
            SessionValidationFailure.ExpiredIdle,
            SessionValidation.validateRecord(record, projectedUserSessionVersion = 1, now = record.idleExpiresAt),
        )
    }

    @Test
    fun `detects missing and mismatched user session version`() {
        val record = SessionRecordCodec.createNew(userId = userId, userSessionVersion = 2, issuedAt = issuedAt)
        val now = issuedAt + 1.days

        assertEquals(
            SessionValidationFailure.MissingUserSessionVersion,
            SessionValidation.validateRecord(record, projectedUserSessionVersion = null, now = now),
        )
        assertEquals(
            SessionValidationFailure.VersionMismatch,
            SessionValidation.validateRecord(record, projectedUserSessionVersion = 1, now = now),
        )
    }

    @Test
    fun `touch threshold is 24 hours since last touch`() {
        val record = SessionRecordCodec.createNew(userId = userId, userSessionVersion = 1, issuedAt = issuedAt)

        assertFalse(SessionValidation.shouldTouchSession(record, now = issuedAt + 23.hours))
        assertTrue(SessionValidation.shouldTouchSession(record, now = issuedAt + 24.hours))
    }
}

class SessionLifetimeTest {
    @Test
    fun `configured lifetimes match PRD`() {
        assertEquals(30.days, SessionLifetime.idleLifetime)
        assertEquals(180.days, SessionLifetime.absoluteLifetime)
        assertEquals(24.hours, SessionLifetime.touchThreshold)
        assertEquals(10.minutes, SessionLifetime.gatewayTouchDebounce)
        assertNotEquals(SessionLifetime.idleLifetime, SessionLifetime.absoluteLifetime)
    }
}
