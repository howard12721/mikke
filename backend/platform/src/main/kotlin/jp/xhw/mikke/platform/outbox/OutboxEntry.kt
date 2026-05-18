package jp.xhw.mikke.platform.outbox

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class OutboxEntry(
    val id: Uuid,
    val eventType: String,
    val eventVersion: Int = 1,
    val aggregateType: String,
    val aggregateId: Uuid,
    val payloadJson: String,
    val correlationId: String? = null,
    val causationId: Uuid? = null,
    val createdAt: Instant,
    val publishedAt: Instant? = null,
    val publishAttempts: Int = 0,
)
