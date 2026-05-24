package jp.xhw.mikke.services.guess.worker

import jp.xhw.mikke.platform.events.ProcessedEventMarkResult
import jp.xhw.mikke.platform.events.ProcessedEventStore
import kotlin.uuid.Uuid

interface ProcessedEventGate {
    fun tryMarkProcessed(
        eventId: Uuid,
        eventType: String,
    ): ProcessedEventMarkResult
}

class ProcessedEventStoreGate(
    private val store: ProcessedEventStore,
) : ProcessedEventGate {
    override fun tryMarkProcessed(
        eventId: Uuid,
        eventType: String,
    ): ProcessedEventMarkResult = store.tryMarkProcessed(eventId, eventType)
}
