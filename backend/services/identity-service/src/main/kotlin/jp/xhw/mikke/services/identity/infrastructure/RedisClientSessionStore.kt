package jp.xhw.mikke.services.identity.infrastructure

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import jp.xhw.mikke.platform.auth.session.SessionKeys
import jp.xhw.mikke.platform.auth.session.SessionRecord
import jp.xhw.mikke.platform.auth.session.SessionRecordCodec
import jp.xhw.mikke.services.identity.application.port.ClientSessionStore
import kotlin.time.Clock

class RedisClientSessionStore(
    private val commands: RedisCommands<String, String>,
    private val clock: Clock = Clock.System,
) : ClientSessionStore {
    override fun saveSession(
        sessionHash: String,
        record: SessionRecord,
    ) {
        val key = SessionKeys.sessionKey(sessionHash)
        val payload = SessionRecordCodec.serialize(record)
        val ttlSeconds = SessionRecordCodec.redisTtlSeconds(record, clock.now())
        commands.setex(key, ttlSeconds, payload)
    }

    override fun findSession(sessionHash: String): SessionRecord? {
        val key = SessionKeys.sessionKey(sessionHash)
        val payload = commands.get(key) ?: return null
        return SessionRecordCodec.deserialize(payload)
    }

    override fun touchSession(
        sessionHash: String,
        record: SessionRecord,
    ) {
        saveSession(sessionHash, record)
    }

    override fun deleteSession(sessionHash: String): Boolean {
        val key = SessionKeys.sessionKey(sessionHash)
        return commands.del(key) > 0
    }

    override fun saveUserSessionVersion(
        userId: String,
        version: Int,
    ) {
        val key = SessionKeys.userSessionVersionKey(userId)
        commands.eval<Long>(
            SAVE_USER_SESSION_VERSION_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            version.toString(),
        )
    }

    private companion object {
        private const val SAVE_USER_SESSION_VERSION_SCRIPT =
            """
            local current = redis.call('GET', KEYS[1])
            local current_version = tonumber(current)
            local next_version = tonumber(ARGV[1])
            if current == false or current_version == nil or current_version <= next_version then
                redis.call('SET', KEYS[1], ARGV[1])
                return next_version
            end
            return current_version
            """
    }
}
