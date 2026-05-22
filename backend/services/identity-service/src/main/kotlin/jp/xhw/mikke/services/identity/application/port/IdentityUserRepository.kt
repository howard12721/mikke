package jp.xhw.mikke.services.identity.application.port

import jp.xhw.mikke.services.identity.application.pagination.SearchUsersCursor
import jp.xhw.mikke.services.identity.model.*
import kotlin.time.Instant

interface IdentityUserRepository {
    fun saveUser(user: IdentityUser)

    fun findByLogin(login: String): IdentityUser?

    fun findByEmails(emails: List<Email>): List<IdentityUser>

    fun findByIds(ids: List<UserId>): List<IdentityUser>

    fun searchByUsernamePrefix(
        normalizedPrefix: String,
        limit: Int,
        cursor: SearchUsersCursor?,
    ): List<IdentityUser>

    fun updateProfile(user: IdentityUser)

    fun deactivate(
        userId: UserId,
        deactivatedAt: Instant,
        updatedAt: Instant,
    ): Boolean
}
