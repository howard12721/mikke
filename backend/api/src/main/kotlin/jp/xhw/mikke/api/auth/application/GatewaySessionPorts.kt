package jp.xhw.mikke.api.auth.application

import jp.xhw.mikke.platform.auth.session.SessionRecord

interface GatewaySessionReader {
    fun findSession(sessionHash: String): SessionRecord?

    fun findUserSessionVersion(userId: String): Int?
}

interface IdentitySessionGateway : AutoCloseable {
    suspend fun touchSession(sessionHash: String)

    override fun close() {}
}

enum class GatewaySessionAuthFailure {
    MalformedHeader,
    MalformedSessionId,
    MissingSession,
    MalformedPayload,
    ExpiredIdle,
    ExpiredAbsolute,
    MissingUserSessionVersion,
    VersionMismatch,
    RedisFailure,
}
