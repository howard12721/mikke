package jp.xhw.mikke.services.identity.infrastructure

import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.identity.application.port.RefreshSessionRepository
import jp.xhw.mikke.services.identity.model.RefreshSession
import jp.xhw.mikke.services.identity.model.RefreshSessionId
import jp.xhw.mikke.services.identity.model.UserId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

class ExposedRefreshSessionRepository : RefreshSessionRepository {
    override fun save(session: RefreshSession) {
        IdentityRefreshSessionsTable.insert { row ->
            row[id] = session.id.value
            row[userId] = session.userId.value
            row[refreshTokenHash] = session.refreshTokenHash
            row[expiresAt] = session.expiresAt.toJavaInstant()
            row[revokedAt] = session.revokedAt?.toJavaInstant()
            row[createdAt] = session.createdAt.toJavaInstant()
        }
    }

    override fun findByRefreshTokenHash(refreshTokenHash: String): RefreshSession? =
        IdentityRefreshSessionsTable
            .selectAll()
            .where { IdentityRefreshSessionsTable.refreshTokenHash eq refreshTokenHash }
            .limit(1)
            .singleOrNull()
            ?.toRefreshSession()

    override fun revoke(
        sessionId: RefreshSessionId,
        revokedAt: Instant,
    ): Boolean =
        IdentityRefreshSessionsTable.update(
            where = {
                (IdentityRefreshSessionsTable.id eq sessionId.value) and
                    (IdentityRefreshSessionsTable.revokedAt eq null)
            },
        ) { row ->
            row[IdentityRefreshSessionsTable.revokedAt] = revokedAt.toJavaInstant()
        } > 0

    override fun revokeByRefreshTokenHash(
        refreshTokenHash: String,
        revokedAt: Instant,
    ): Boolean =
        IdentityRefreshSessionsTable.update(
            where = {
                (IdentityRefreshSessionsTable.refreshTokenHash eq refreshTokenHash) and
                    (IdentityRefreshSessionsTable.revokedAt eq null)
            },
        ) { row ->
            row[IdentityRefreshSessionsTable.revokedAt] = revokedAt.toJavaInstant()
        } > 0

    override fun revokeAllForUser(
        userId: UserId,
        revokedAt: Instant,
    ) {
        IdentityRefreshSessionsTable.update(
            where = {
                (IdentityRefreshSessionsTable.userId eq userId.value) and
                    (IdentityRefreshSessionsTable.revokedAt eq null)
            },
        ) { row ->
            row[IdentityRefreshSessionsTable.revokedAt] = revokedAt.toJavaInstant()
        }
    }
}

private object IdentityRefreshSessionsTable : Table("identity_refresh_sessions") {
    val id = uuidBinary("id")
    val userId = uuidBinary("user_id")
    val refreshTokenHash = varchar("refresh_token_hash", length = 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

private fun ResultRow.toRefreshSession(): RefreshSession =
    RefreshSession(
        id = RefreshSessionId(this[IdentityRefreshSessionsTable.id]),
        userId = UserId(this[IdentityRefreshSessionsTable.userId]),
        refreshTokenHash = this[IdentityRefreshSessionsTable.refreshTokenHash],
        expiresAt = this[IdentityRefreshSessionsTable.expiresAt].toKotlinInstant(),
        revokedAt = this[IdentityRefreshSessionsTable.revokedAt]?.toKotlinInstant(),
        createdAt = this[IdentityRefreshSessionsTable.createdAt].toKotlinInstant(),
    )
