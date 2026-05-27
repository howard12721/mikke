package jp.xhw.mikke.api.auth.infrastructure

import io.lettuce.core.api.sync.RedisCommands
import jp.xhw.mikke.api.auth.application.GatewaySessionReader
import jp.xhw.mikke.platform.auth.session.SessionKeys
import jp.xhw.mikke.platform.auth.session.SessionRecord
import jp.xhw.mikke.platform.auth.session.SessionRecordCodec

class RedisGatewaySessionReader(
    private val commands: RedisCommands<String, String>,
) : GatewaySessionReader {
    override fun findSession(sessionHash: String): SessionRecord? {
        val payload = commands.get(SessionKeys.sessionKey(sessionHash)) ?: return null
        return SessionRecordCodec.deserialize(payload)
    }

    override fun findUserSessionVersion(userId: String): Int? {
        val raw = commands.get(SessionKeys.userSessionVersionKey(userId)) ?: return null
        return raw.toIntOrNull()
    }
}
