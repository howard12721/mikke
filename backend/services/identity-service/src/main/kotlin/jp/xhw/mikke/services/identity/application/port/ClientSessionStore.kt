package jp.xhw.mikke.services.identity.application.port

import jp.xhw.mikke.platform.auth.session.SessionRecord

interface ClientSessionStore {
    fun saveSession(
        sessionHash: String,
        record: SessionRecord,
    )

    fun findSession(sessionHash: String): SessionRecord?

    fun touchSession(
        sessionHash: String,
        record: SessionRecord,
    )

    fun deleteSession(sessionHash: String): Boolean

    /**
     * Project the latest known user session version without allowing older writers to regress it.
     */
    fun saveUserSessionVersion(
        userId: String,
        version: Int,
    )
}
