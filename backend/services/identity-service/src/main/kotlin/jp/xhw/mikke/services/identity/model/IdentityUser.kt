package jp.xhw.mikke.services.identity.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class IdentityUser(
    val id: UserId,
    val email: Email,
    val username: Username,
    val displayName: DisplayName,
    val passwordHash: PasswordHash,
    val avatarMediaId: AvatarMediaId?,
    val status: IdentityUserStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deactivatedAt: Instant?,
) {
    fun isPubliclyVisible(): Boolean = status == IdentityUserStatus.ACTIVE
}

@JvmInline
value class AvatarMediaId(
    val value: Uuid,
)

@JvmInline
value class Email(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "email must not be blank" }
        require(value.contains("@")) { "email must contain '@'" }
    }
}

@JvmInline
value class Username(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "username must not be blank" }
        require(value.length in 3..32) { "username must be between 3 and 20 characters" }
        require(value.all { it.isLetterOrDigit() || it == '_' }) { "username must contain letters" }
    }
}

@JvmInline
value class DisplayName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "displayName must not be blank" }
    }
}

data class PasswordHash(
    val iterations: Int,
    val hash: String,
    val salt: String,
) {
    init {
        require(iterations > 0) { "iterations must be greater than 0" }
        require(hash.isNotBlank()) { "passwordHash must not be blank" }
        require(salt.isNotBlank()) { "passwordHash must contain 'salt'" }
    }
}
