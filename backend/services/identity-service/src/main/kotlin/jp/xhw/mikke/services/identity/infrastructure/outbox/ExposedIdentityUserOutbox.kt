package jp.xhw.mikke.services.identity.infrastructure.outbox

import jp.xhw.mikke.events.user.UserEventTypes
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.outbox.exposed.insertEntry
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.identity.application.port.IdentityUserOutbox
import jp.xhw.mikke.services.identity.model.IdentityUser
import jp.xhw.mikke.services.identity.model.UserId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedIdentityUserOutbox(
    private val clock: Clock = Clock.System,
) : IdentityUserOutbox {
    override fun appendUserCreated(user: IdentityUser) {
        val payload =
            UserCreatedPayload(
                userId = formatGrpcUuid(user.id.value),
                username = user.username.value,
                displayName = user.displayName.value,
                createdAt = user.createdAt.toString(),
            )

        insert(
            eventType = UserEventTypes.CREATED,
            aggregateId = user.id.value,
            payloadJson = json.encodeToString(payload),
        )
    }

    override fun appendProfileUpdated(user: IdentityUser) {
        val payload =
            UserProfileUpdatedPayload(
                userId = formatGrpcUuid(user.id.value),
                username = user.username.value,
                displayName = user.displayName.value,
                avatarMediaId = user.avatarMediaId?.let { formatGrpcUuid(it.value) },
                updatedAt = user.updatedAt.toString(),
            )

        insert(
            eventType = UserEventTypes.PROFILE_UPDATED,
            aggregateId = user.id.value,
            payloadJson = json.encodeToString(payload),
        )
    }

    override fun appendUserDeactivated(
        userId: UserId,
        deactivatedAt: Instant,
    ) {
        val payload =
            UserDeactivatedPayload(
                userId = formatGrpcUuid(userId.value),
                deactivatedAt = deactivatedAt.toString(),
            )

        insert(
            eventType = UserEventTypes.DEACTIVATED,
            aggregateId = userId.value,
            payloadJson = json.encodeToString(payload),
        )
    }

    private fun insert(
        eventType: String,
        aggregateId: Uuid,
        payloadJson: String,
    ) {
        IdentityOutboxTable.insertEntry(
            OutboxEntry(
                id = Uuid.random(),
                eventType = eventType,
                aggregateType = AGGREGATE_TYPE,
                aggregateId = aggregateId,
                payloadJson = payloadJson,
                createdAt = clock.now(),
            ),
        )
    }

    private companion object {
        const val AGGREGATE_TYPE = "user"

        val json = Json { encodeDefaults = false }
    }
}

@Serializable
private data class UserCreatedPayload(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class UserProfileUpdatedPayload(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_media_id") val avatarMediaId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class UserDeactivatedPayload(
    @SerialName("user_id") val userId: String,
    @SerialName("deactivated_at") val deactivatedAt: String,
)
