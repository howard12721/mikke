package jp.xhw.mikke.platform.events

import jp.xhw.mikke.platform.events.exposed.ProcessedEventsTable
import jp.xhw.mikke.platform.events.exposed.exists
import jp.xhw.mikke.platform.events.exposed.find
import jp.xhw.mikke.platform.events.exposed.tryMarkProcessed
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ProcessedEventStore(
    private val table: ProcessedEventsTable,
) {
    fun exists(eventId: Uuid): Boolean = table.exists(eventId)

    fun find(eventId: Uuid): ProcessedEvent? = table.find(eventId)

    fun tryMarkProcessed(
        eventId: Uuid,
        eventType: String,
        processedAt: Instant =
            kotlin.time.Clock.System
                .now(),
    ): ProcessedEventMarkResult = table.tryMarkProcessed(eventId, eventType, processedAt)
}
