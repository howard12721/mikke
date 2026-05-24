package jp.xhw.mikke.platform.events.subscription

import kotlin.time.Instant

enum class DeadLetterFailureCategory {
    MALFORMED,
    UNSUPPORTED_EVENT_TYPE,
    UNSUPPORTED_EVENT_VERSION,
    HANDLER_FAILED,
}

data class DeadLetterEvent(
    val originalStreamName: String,
    val originalMessageId: String,
    val consumerGroup: String,
    val consumerName: String,
    val eventId: String?,
    val eventType: String?,
    val eventVersion: Int?,
    val failureCategory: DeadLetterFailureCategory,
    val failureMessage: String,
    val rawFields: Map<String, String>,
    val failedAt: Instant,
)

fun interface DeadLetterSink {
    fun write(deadLetter: DeadLetterEvent)
}
