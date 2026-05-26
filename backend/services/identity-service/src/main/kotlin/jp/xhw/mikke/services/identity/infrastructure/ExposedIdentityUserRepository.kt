package jp.xhw.mikke.services.identity.infrastructure

import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.platform.uuid.exposed.uuidBinaryNullable
import jp.xhw.mikke.services.identity.application.exception.DuplicateIdentityUserException
import jp.xhw.mikke.services.identity.application.pagination.SearchUsersCursor
import jp.xhw.mikke.services.identity.application.port.IdentityUserRepository
import jp.xhw.mikke.services.identity.model.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

class ExposedIdentityUserRepository : IdentityUserRepository {
    override fun saveUser(user: IdentityUser) {
        try {
            IdentityUsersTable.insert { row ->
                row[id] = user.id.value
                row[email] = user.email.value
                row[normalizedEmail] = user.email.value.normalizeEmail()
                row[username] = user.username.value
                row[normalizedUsername] = user.username.value.normalizeUsername()
                row[displayName] = user.displayName.value
                row[avatarMediaId] = user.avatarMediaId?.value
                row[status] = user.status.toDatabaseValue()
                row[passwordHashIterations] = user.passwordHash.iterations
                row[passwordHash] = user.passwordHash.hash
                row[passwordSalt] = user.passwordHash.salt
                row[createdAt] = user.createdAt.toJavaInstant()
                row[updatedAt] = user.updatedAt.toJavaInstant()
                row[deactivatedAt] = user.deactivatedAt?.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueConstraintViolation()) {
                throw DuplicateIdentityUserException(cause = e)
            }
            throw e
        }
    }

    override fun findByLogin(login: String): IdentityUser? {
        val trimmedLogin = login.trim()
        if (trimmedLogin.isEmpty()) {
            return null
        }

        return IdentityUsersTable
            .selectAll()
            .where {
                (IdentityUsersTable.normalizedEmail eq trimmedLogin.normalizeEmail()) or
                    (IdentityUsersTable.normalizedUsername eq trimmedLogin.normalizeUsername())
            }.limit(1)
            .singleOrNull()
            ?.toIdentityUser()
    }

    override fun findByEmails(emails: List<Email>): List<IdentityUser> {
        if (emails.isEmpty()) {
            return emptyList()
        }

        val byEmail =
            IdentityUsersTable
                .selectAll()
                .where {
                    IdentityUsersTable.normalizedEmail inList emails.map { it.value.normalizeEmail() }.distinct()
                }.map { it.toIdentityUser() }
                .associateBy { it.email.value.normalizeEmail() }

        return emails.mapNotNull { byEmail[it.value.normalizeEmail()] }
    }

    override fun findByIds(ids: List<UserId>): List<IdentityUser> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        val byId =
            IdentityUsersTable
                .selectAll()
                .where { IdentityUsersTable.id inList ids.map { it.value }.distinct() }
                .map { it.toIdentityUser() }
                .associateBy { it.id.value }

        return ids.mapNotNull { byId[it.value] }
    }

    override fun searchByUsernamePrefix(
        normalizedPrefix: String,
        limit: Int,
        cursor: SearchUsersCursor?,
    ): List<IdentityUser> {
        if (normalizedPrefix.isEmpty() || limit <= 0) {
            return emptyList()
        }

        val prefixCondition =
            IdentityUsersTable.normalizedUsername like
                (LikePattern.ofLiteral(normalizedPrefix) + "%")
        val activeCondition = IdentityUsersTable.status eq IdentityUserStatus.ACTIVE.toDatabaseValue()

        val cursorCondition =
            cursor?.let {
                (IdentityUsersTable.normalizedUsername greater it.normalizedUsername) or
                    (
                        (IdentityUsersTable.normalizedUsername eq it.normalizedUsername) and
                            (IdentityUsersTable.id greater it.id)
                    )
            }

        val whereClause =
            when (cursorCondition) {
                null -> prefixCondition and activeCondition
                else -> prefixCondition and activeCondition and cursorCondition
            }

        return IdentityUsersTable
            .selectAll()
            .where { whereClause }
            .orderBy(IdentityUsersTable.normalizedUsername to SortOrder.ASC, IdentityUsersTable.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toIdentityUser() }
    }

    override fun updateProfile(user: IdentityUser) {
        try {
            IdentityUsersTable.update({ IdentityUsersTable.id eq user.id.value }) { row ->
                row[username] = user.username.value
                row[normalizedUsername] = user.username.value.normalizeUsername()
                row[displayName] = user.displayName.value
                row[avatarMediaId] = user.avatarMediaId?.value
                row[updatedAt] = user.updatedAt.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueConstraintViolation()) {
                throw DuplicateIdentityUserException(cause = e)
            }
            throw e
        }
    }

    override fun deactivate(
        userId: UserId,
        deactivatedAt: Instant,
        updatedAt: Instant,
    ): Boolean =
        IdentityUsersTable.update({ IdentityUsersTable.id eq userId.value }) { row ->
            row[IdentityUsersTable.status] = IdentityUserStatus.DEACTIVATED.toDatabaseValue()
            row[IdentityUsersTable.deactivatedAt] = deactivatedAt.toJavaInstant()
            row[IdentityUsersTable.updatedAt] = updatedAt.toJavaInstant()
        } > 0

    override fun changePassword(
        userId: UserId,
        passwordHash: PasswordHash,
        updatedAt: Instant,
    ): Boolean =
        IdentityUsersTable.update({ IdentityUsersTable.id eq userId.value }) { row ->
            row[IdentityUsersTable.passwordHashIterations] = passwordHash.iterations
            row[IdentityUsersTable.passwordHash] = passwordHash.hash
            row[IdentityUsersTable.passwordSalt] = passwordHash.salt
            row[IdentityUsersTable.updatedAt] = updatedAt.toJavaInstant()
        } > 0
}

private object IdentityUsersTable : Table("identity_users") {
    val id = uuidBinary("id")
    val email = varchar("email", length = 255)
    val normalizedEmail = varchar("normalized_email", length = 255).uniqueIndex()
    val username = varchar("username", length = 32)
    val normalizedUsername = varchar("normalized_username", length = 32).uniqueIndex()
    val displayName = varchar("display_name", length = 255)
    val avatarMediaId = uuidBinaryNullable("avatar_media_id")
    val status = varchar("status", length = 32)
    val passwordHashIterations = integer("password_hash_iterations")
    val passwordHash = varchar("password_hash", length = 512)
    val passwordSalt = varchar("password_salt", length = 512)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deactivatedAt = timestamp("deactivated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

private fun ResultRow.toIdentityUser(): IdentityUser =
    IdentityUser(
        id = UserId(this[IdentityUsersTable.id]),
        email = Email(this[IdentityUsersTable.email]),
        username = Username(this[IdentityUsersTable.username]),
        displayName = DisplayName(this[IdentityUsersTable.displayName]),
        passwordHash =
            PasswordHash(
                iterations = this[IdentityUsersTable.passwordHashIterations],
                hash = this[IdentityUsersTable.passwordHash],
                salt = this[IdentityUsersTable.passwordSalt],
            ),
        avatarMediaId = this[IdentityUsersTable.avatarMediaId]?.let { AvatarMediaId(it) },
        status = IdentityUserStatus.fromDatabaseValue(this[IdentityUsersTable.status]),
        createdAt = this[IdentityUsersTable.createdAt].toKotlinInstant(),
        updatedAt = this[IdentityUsersTable.updatedAt].toKotlinInstant(),
        deactivatedAt = this[IdentityUsersTable.deactivatedAt]?.toKotlinInstant(),
    )

private fun String.normalizeEmail(): String = trim().lowercase()

private fun String.normalizeUsername(): String = trim().lowercase()
