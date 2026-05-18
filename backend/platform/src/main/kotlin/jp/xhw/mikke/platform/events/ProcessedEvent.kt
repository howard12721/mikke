package jp.xhw.mikke.platform.events

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class ProcessedEvent(
    val eventId: Uuid,
    val eventType: String,
    val processedAt: Instant,
)

sealed interface ProcessedEventMarkResult {
    data object Recorded : ProcessedEventMarkResult

    data object AlreadyProcessed : ProcessedEventMarkResult
}
