package jp.xhw.mikke.platform.outbox.exposed

import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.platform.uuid.exposed.uuidBinaryNullable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
    }
}

fun OutboxTable.selectUnpublished(limit: Int): List<OutboxEntry> =
    selectAll()
        .where { publishedAt.isNull() }
        .orderBy(createdAt to SortOrder.ASC, id to SortOrder.ASC)
        .limit(limit)
        .map { it.toOutboxEntry(this) }

fun OutboxTable.markPublished(
    ids: Collection<Uuid>,
    publishedAt: Instant,
) {
    if (ids.isEmpty()) {
        return
    }

    update({ (id inList ids) and this.publishedAt.isNull() }) {
        it[this.publishedAt] = publishedAt.toJavaInstant()
    }
}

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
    )
