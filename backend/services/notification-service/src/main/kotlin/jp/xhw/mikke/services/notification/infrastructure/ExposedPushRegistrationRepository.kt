package jp.xhw.mikke.services.notification.infrastructure

import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.services.notification.application.PushRegistrationRepository
import jp.xhw.mikke.services.notification.model.PushPlatform
import jp.xhw.mikke.services.notification.model.PushRegistration
import jp.xhw.mikke.services.notification.model.StoredPushRegistration
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedPushRegistrationRepository : PushRegistrationRepository {
    override fun upsert(registration: StoredPushRegistration): PushRegistration {
        val incoming = registration.registration

        PushRegistrationsTable.deleteWhere {
            (platform eq incoming.platform) and
                (registrationHash eq registration.registrationHash) and
                (
                    (userId neq incoming.userId) or
                        (deviceId neq incoming.deviceId)
                )
        }

        val existing =
            PushRegistrationsTable
                .selectAll()
                .where {
                    (PushRegistrationsTable.userId eq incoming.userId) and
                        (PushRegistrationsTable.deviceId eq incoming.deviceId) and
                        (PushRegistrationsTable.platform eq incoming.platform)
                }.singleOrNull()

        if (existing == null) {
            PushRegistrationsTable.insert { row ->
                row[id] = incoming.id
                row[userId] = incoming.userId
                row[deviceId] = incoming.deviceId
                row[platform] = incoming.platform
                row[registrationHash] = registration.registrationHash
                row[registrationEncrypted] = registration.encryptedInstallationId
                row[enabled] = true
                row[createdAt] = incoming.createdAt.toJavaInstant()
                row[lastSeenAt] = incoming.lastSeenAt.toJavaInstant()
                row[disabledAt] = null
            }
            return incoming
        }

        val existingId = existing[PushRegistrationsTable.id]
        PushRegistrationsTable.update({ PushRegistrationsTable.id eq existingId }) { row ->
            row[registrationHash] = registration.registrationHash
            row[registrationEncrypted] = registration.encryptedInstallationId
            row[enabled] = true
            row[lastSeenAt] = incoming.lastSeenAt.toJavaInstant()
            row[disabledAt] = null
        }
        return existing.toPushRegistration().copy(enabled = true, lastSeenAt = incoming.lastSeenAt)
    }

    override fun disable(
        userId: Uuid,
        deviceId: String,
        platform: PushPlatform,
        disabledAt: Instant,
    ) {
        PushRegistrationsTable.update({
            (PushRegistrationsTable.userId eq userId) and
                (PushRegistrationsTable.deviceId eq deviceId) and
                (PushRegistrationsTable.platform eq platform)
        }) { row ->
            row[enabled] = false
            row[PushRegistrationsTable.disabledAt] = disabledAt.toJavaInstant()
        }
    }
}

private fun ResultRow.toPushRegistration(): PushRegistration =
    PushRegistration(
        id = this[PushRegistrationsTable.id],
        userId = this[PushRegistrationsTable.userId],
        deviceId = this[PushRegistrationsTable.deviceId],
        platform = this[PushRegistrationsTable.platform],
        enabled = this[PushRegistrationsTable.enabled],
        createdAt = this[PushRegistrationsTable.createdAt].toKotlinInstant(),
        lastSeenAt = this[PushRegistrationsTable.lastSeenAt].toKotlinInstant(),
    )
