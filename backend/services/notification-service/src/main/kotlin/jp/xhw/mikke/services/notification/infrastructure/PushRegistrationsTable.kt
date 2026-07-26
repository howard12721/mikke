package jp.xhw.mikke.services.notification.infrastructure

import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.notification.model.PushPlatform
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

object PushRegistrationsTable : Table("push_tokens") {
    val id = uuidBinary("id")
    val userId = uuidBinary("user_id")
    val deviceId = varchar("device_id", 128)
    val platform = enumerationByName<PushPlatform>("platform", 32)
    val registrationHash = char("registration_hash", 64)
    val registrationEncrypted = text("registration_encrypted")
    val enabled = bool("enabled")
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at")
    val disabledAt = timestamp("disabled_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_push_tokens_platform_token_hash", platform, registrationHash)
        uniqueIndex("uq_push_tokens_user_device_platform", userId, deviceId, platform)
    }
}
