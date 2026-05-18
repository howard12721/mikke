package jp.xhw.mikke.platform.events.exposed

import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import jp.xhw.mikke.platform.events.ProcessedEvent
import jp.xhw.mikke.platform.events.ProcessedEventMarkResult
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

abstract class ProcessedEventsTable(
    tableName: String,
) : Table(tableName) {
    val eventId = uuidBinary("event_id")
    val eventType = varchar("event_type", length = 128)
    val processedAt = timestamp("processed_at")

    override val primaryKey = PrimaryKey(eventId)
}

fun ProcessedEventsTable.tryMarkProcessed(
    eventId: Uuid,
    eventType: String,
    processedAt: Instant = Clock.System.now(),
): ProcessedEventMarkResult =
    try {
        insert {
            it[this.eventId] = eventId
            it[this.eventType] = eventType
            it[this.processedAt] = processedAt.toJavaInstant()
        }
        ProcessedEventMarkResult.Recorded
    } catch (e: ExposedSQLException) {
        if (e.isUniqueConstraintViolation()) {
            ProcessedEventMarkResult.AlreadyProcessed
        } else {
            throw e
        }
    }

fun ProcessedEventsTable.exists(eventId: Uuid): Boolean =
    selectAll()
        .where { this.eventId eq eventId }
        .limit(1)
        .any()

fun ProcessedEventsTable.find(eventId: Uuid): ProcessedEvent? =
    selectAll()
        .where { this.eventId eq eventId }
        .limit(1)
        .firstOrNull()
        ?.toProcessedEvent(this)

private fun ResultRow.toProcessedEvent(table: ProcessedEventsTable): ProcessedEvent =
    ProcessedEvent(
        eventId = this[table.eventId],
        eventType = this[table.eventType],
        processedAt = this[table.processedAt].toKotlinInstant(),
    )
