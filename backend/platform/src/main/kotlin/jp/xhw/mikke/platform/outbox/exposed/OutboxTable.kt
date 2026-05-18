package jp.xhw.mikke.platform.outbox.exposed

import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.platform.uuid.exposed.uuidBinaryNullable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

abstract class OutboxTable(
    tableName: String,
) : Table(tableName) {
    val id = uuidBinary("id")
    val eventType = varchar("event_type", length = 128)
    val eventVersion = integer("event_version")
    val aggregateType = varchar("aggregate_type", length = 64)
    val aggregateId = uuidBinary("aggregate_id")
    val payloadJson = text("payload_json")
    val correlationId = varchar("correlation_id", length = 128).nullable()
    val causationId = uuidBinaryNullable("causation_id")
    val createdAt = timestamp("created_at")
    val publishedAt = timestamp("published_at").nullable()
    val publishingBy = varchar("publishing_by", length = 128).nullable()
    val publishingUntil = timestamp("publishing_until").nullable()
    val publishAttempts = integer("publish_attempts")
    val lastPublishError = text("last_publish_error").nullable()

    override val primaryKey = PrimaryKey(id)
}

fun OutboxTable.insertEntry(entry: OutboxEntry) {
    insert {
        it[id] = entry.id
        it[eventType] = entry.eventType
        it[eventVersion] = entry.eventVersion
        it[aggregateType] = entry.aggregateType
        it[aggregateId] = entry.aggregateId
        it[payloadJson] = entry.payloadJson
        it[correlationId] = entry.correlationId
        it[causationId] = entry.causationId
        it[createdAt] = entry.createdAt.toJavaInstant()
        it[publishedAt] = entry.publishedAt?.toJavaInstant()
        it[publishingBy] = null
        it[publishingUntil] = null
        it[publishAttempts] = 0
        it[lastPublishError] = null
    }
}

fun OutboxTable.claimForPublishing(
    publisherId: String,
    limit: Int,
    leaseUntil: Instant,
    now: Instant = Clock.System.now(),
): List<OutboxEntry> {
    if (limit <= 0) {
        return emptyList()
    }

    val nowJava = now.toJavaInstant()
    val candidates =
        selectAll()
            .where {
                publishedAt.isNull() and (publishingUntil.isNull() or (publishingUntil less nowJava))
            }.orderBy(createdAt to SortOrder.ASC, id to SortOrder.ASC)
            .limit(limit)
            .map { it.toOutboxEntry(this) }

    return candidates.filter { entry ->
        update({
            (id eq entry.id) and
                publishedAt.isNull() and
                (publishingUntil.isNull() or (publishingUntil less nowJava))
        }) {
            it[publishingBy] = publisherId
            it[publishingUntil] = leaseUntil.toJavaInstant()
            it[publishAttempts] = entry.publishAttempts + 1
            it[lastPublishError] = null
        } == 1
    }
}

fun OutboxTable.markPublishedByPublisher(
    id: Uuid,
    publisherId: String,
    publishedAt: Instant,
): Boolean =
    update({ (this.id eq id) and (this.publishedAt.isNull()) and (publishingBy eq publisherId) }) {
        it[this.publishedAt] = publishedAt.toJavaInstant()
        it[publishingBy] = null
        it[publishingUntil] = null
        it[lastPublishError] = null
    } == 1

fun OutboxTable.releasePublishClaim(
    id: Uuid,
    publisherId: String,
    error: String,
): Boolean =
    update({ (this.id eq id) and (publishedAt.isNull()) and (publishingBy eq publisherId) }) {
        it[publishingBy] = null
        it[publishingUntil] = null
        it[lastPublishError] = error.take(4096)
    } == 1

private fun ResultRow.toOutboxEntry(table: OutboxTable): OutboxEntry =
    OutboxEntry(
        id = this[table.id],
        eventType = this[table.eventType],
        eventVersion = this[table.eventVersion],
        aggregateType = this[table.aggregateType],
        aggregateId = this[table.aggregateId],
        payloadJson = this[table.payloadJson],
        correlationId = this[table.correlationId],
        causationId = this[table.causationId],
        createdAt = this[table.createdAt].toKotlinInstant(),
        publishedAt = this[table.publishedAt]?.toKotlinInstant(),
        publishAttempts = this[table.publishAttempts],
    )
