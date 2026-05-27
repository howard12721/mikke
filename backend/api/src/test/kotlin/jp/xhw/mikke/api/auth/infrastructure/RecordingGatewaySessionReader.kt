package jp.xhw.mikke.api.auth.infrastructure

import jp.xhw.mikke.api.auth.application.GatewaySessionReader
import jp.xhw.mikke.platform.auth.session.SessionRecord
import jp.xhw.mikke.platform.auth.session.SessionRecordCodec

class RecordingGatewaySessionReader : GatewaySessionReader {
    val sessions = mutableMapOf<String, SessionRecord>()
    val versions = mutableMapOf<String, Int>()
    var failOnSessionRead: Boolean = false
    var failOnVersionRead: Boolean = false

    fun putSession(
        sessionHash: String,
        userId: String,
        version: Int,
        issuedAt: kotlin.time.Instant,
    ) {
        sessions[sessionHash] =
            SessionRecordCodec.createNew(
                userId = userId,
                userSessionVersion = version,
                issuedAt = issuedAt,
            )
        versions[userId] = version
    }

    override fun findSession(sessionHash: String): SessionRecord? {
        if (failOnSessionRead) {
            throw IllegalStateException("redis unavailable")
        }
        return sessions[sessionHash]
    }

    override fun findUserSessionVersion(userId: String): Int? {
        if (failOnVersionRead) {
            throw IllegalStateException("redis unavailable")
        }
        return versions[userId]
    }
}
