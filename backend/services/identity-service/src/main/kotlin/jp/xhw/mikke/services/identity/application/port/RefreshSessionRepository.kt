package jp.xhw.mikke.services.identity.application.port

import jp.xhw.mikke.services.identity.model.RefreshSession
import jp.xhw.mikke.services.identity.model.RefreshSessionId
import jp.xhw.mikke.services.identity.model.UserId
import kotlin.time.Instant

interface RefreshSessionRepository {
    fun save(session: RefreshSession)

    fun findByRefreshTokenHash(refreshTokenHash: String): RefreshSession?

    fun revoke(
        sessionId: RefreshSessionId,
        revokedAt: Instant,
    ): Boolean

    fun revokeByRefreshTokenHash(
        refreshTokenHash: String,
        revokedAt: Instant,
    ): Boolean

    fun revokeAllForUser(
        userId: UserId,
        revokedAt: Instant,
    )
}
