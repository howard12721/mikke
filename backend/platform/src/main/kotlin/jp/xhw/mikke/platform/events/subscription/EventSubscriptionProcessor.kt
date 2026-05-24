package jp.xhw.mikke.platform.events.subscription

import jp.xhw.mikke.events.core.EventEnvelope
import jp.xhw.mikke.platform.redis.RedisStreamRecord
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal data class ParsedEventMetadata(
    val eventId: String,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: String,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val correlationId: String?,
    val causationId: String?,
    val payloadJson: String,
)

internal sealed interface EventProcessOutcome {
    data object Ack : EventProcessOutcome

    data object DoNotAck : EventProcessOutcome
}

internal class EventSubscriptionProcessor(
    private val consumerGroupName: String,
    private val consumerName: String,
    private val streamName: String,
    private val handlers: List<EventHandlerRegistration<*>>,
    private val deadLetterSink: DeadLetterSink,
    private val deliveryCountProvider: (messageId: String) -> Long,
    private val maxDeliveryAttempts: Int,
    private val ignoredEventTypes: Set<String> = emptySet(),
    private val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val handlersByKey =
        handlers.associateBy { handlerKey(it.eventType, it.eventVersion) }
    private val registeredEventTypes = handlers.map { it.eventType }.toSet()

    init {
        require(handlers.isNotEmpty()) { "handlers must not be empty" }
        require(maxDeliveryAttempts > 0) { "maxDeliveryAttempts must be positive" }
    }

    suspend fun process(
        record: RedisStreamRecord,
        deliveryCount: Long? = null,
    ): EventProcessOutcome {
        val metadata =
            try {
                parseMetadata(record)
            } catch (e: EventParseException) {
                deadLetter(
                    record = record,
                    eventId = e.eventId,
                    eventType = e.eventType,
                    eventVersion = e.eventVersion,
                    category = DeadLetterFailureCategory.MALFORMED,
                    message = e.message ?: "Malformed event",
                )
                return EventProcessOutcome.Ack
            }

        if (metadata.eventType in ignoredEventTypes) {
            return EventProcessOutcome.Ack
        }

        val handler =
            handlersByKey[handlerKey(metadata.eventType, metadata.eventVersion)]
                ?: run {
                    val category =
                        if (metadata.eventType in registeredEventTypes) {
                            DeadLetterFailureCategory.UNSUPPORTED_EVENT_VERSION
                        } else {
                            DeadLetterFailureCategory.UNSUPPORTED_EVENT_TYPE
                        }
                    deadLetter(
                        record = record,
                        eventId = metadata.eventId,
                        eventType = metadata.eventType,
                        eventVersion = metadata.eventVersion,
                        category = category,
                        message = "No handler registered for ${metadata.eventType} version ${metadata.eventVersion}",
                    )
                    return EventProcessOutcome.Ack
                }

        val envelope =
            try {
                buildEnvelope(metadata, handler)
            } catch (e: EventParseException) {
                deadLetter(
                    record = record,
                    eventId = metadata.eventId,
                    eventType = metadata.eventType,
                    eventVersion = metadata.eventVersion,
                    category = DeadLetterFailureCategory.MALFORMED,
                    message = e.message ?: "Malformed event payload",
                )
                return EventProcessOutcome.Ack
            }

        return try {
            handler.invokeParsed(envelope)
            EventProcessOutcome.Ack
        } catch (throwable: Throwable) {
            val resolvedDeliveryCount = resolveDeliveryCount(record.id, deliveryCount)
            if (resolvedDeliveryCount >= maxDeliveryAttempts) {
                deadLetter(
                    record = record,
                    eventId = metadata.eventId,
                    eventType = metadata.eventType,
                    eventVersion = metadata.eventVersion,
                    category = DeadLetterFailureCategory.HANDLER_FAILED,
                    message = throwable.message ?: throwable::class.qualifiedName ?: "Handler failed",
                )
                EventProcessOutcome.Ack
            } else {
                EventProcessOutcome.DoNotAck
            }
        }
    }

    private fun resolveDeliveryCount(
        messageId: String,
        prefetched: Long?,
    ): Long {
        if (prefetched != null && prefetched < maxDeliveryAttempts - 1) {
            return prefetched
        }
        return deliveryCountProvider(messageId)
    }

    private fun parseMetadata(record: RedisStreamRecord): ParsedEventMetadata {
        val eventId =
            record.fields["event_id"]?.takeIf { it.isNotBlank() }
                ?: throw EventParseException(null, null, null, "Redis stream record ${record.id} missing event_id")
        val eventType =
            record.fields["event_type"]?.takeIf { it.isNotBlank() }
                ?: throw EventParseException(eventId, null, null, "Redis stream record ${record.id} missing event_type")
        val eventVersion =
            record.fields["event_version"]?.toIntOrNull()
                ?: throw EventParseException(
                    eventId,
                    eventType,
                    null,
                    "Redis stream record ${record.id} has invalid event_version",
                )
        val occurredAt =
            record.fields["occurred_at"]?.takeIf { it.isNotBlank() }
                ?: throw EventParseException(
                    eventId,
                    eventType,
                    eventVersion,
                    "Redis stream record ${record.id} missing occurred_at",
                )
        val producer =
            record.fields["producer"]?.takeIf { it.isNotBlank() }
                ?: throw EventParseException(
                    eventId,
                    eventType,
                    eventVersion,
                    "Redis stream record ${record.id} missing producer",
                )
        val aggregateType =
            record.fields["aggregate_type"]?.takeIf { it.isNotBlank() }
                ?: throw EventParseException(
                    eventId,
                    eventType,
                    eventVersion,
                    "Redis stream record ${record.id} missing aggregate_type",
                )
        val aggregateId =
            record.fields["aggregate_id"]?.takeIf { it.isNotBlank() }
                ?: throw EventParseException(
                    eventId,
                    eventType,
                    eventVersion,
                    "Redis stream record ${record.id} missing aggregate_id",
                )
        val payloadJson =
            record.fields["payload"]?.takeIf { it.isNotBlank() }
                ?: throw EventParseException(
                    eventId,
                    eventType,
                    eventVersion,
                    "Redis stream record ${record.id} missing payload",
                )

        return ParsedEventMetadata(
            eventId = eventId,
            eventType = eventType,
            eventVersion = eventVersion,
            occurredAt = occurredAt,
            producer = producer,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            correlationId = record.fields["correlation_id"],
            causationId = record.fields["causation_id"],
            payloadJson = payloadJson,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> buildEnvelope(
        metadata: ParsedEventMetadata,
        handler: EventHandlerRegistration<T>,
    ): EventEnvelope<T> {
        val payload =
            try {
                handler.decodePayload(metadata.payloadJson, json)
            } catch (e: SerializationException) {
                throw EventParseException(
                    metadata.eventId,
                    metadata.eventType,
                    metadata.eventVersion,
                    "Redis stream record payload is invalid",
                    e,
                )
            } catch (e: IllegalArgumentException) {
                throw EventParseException(
                    metadata.eventId,
                    metadata.eventType,
                    metadata.eventVersion,
                    "Redis stream record payload is invalid",
                    e,
                )
            }

        return EventEnvelope(
            eventId = metadata.eventId,
            eventType = metadata.eventType,
            eventVersion = metadata.eventVersion,
            occurredAt = metadata.occurredAt,
            producer = metadata.producer,
            aggregateType = metadata.aggregateType,
            aggregateId = metadata.aggregateId,
            correlationId = metadata.correlationId,
            causationId = metadata.causationId,
            payload = payload,
        )
    }

    private fun deadLetter(
        record: RedisStreamRecord,
        eventId: String?,
        eventType: String?,
        eventVersion: Int?,
        category: DeadLetterFailureCategory,
        message: String,
    ) {
        deadLetterSink.write(
            DeadLetterEvent(
                originalStreamName = streamName,
                originalMessageId = record.id,
                consumerGroup = consumerGroupName,
                consumerName = consumerName,
                eventId = eventId,
                eventType = eventType,
                eventVersion = eventVersion,
                failureCategory = category,
                failureMessage = message,
                rawFields = record.fields,
                failedAt = clock.now(),
            ),
        )
    }

    private fun handlerKey(
        eventType: String,
        eventVersion: Int,
    ): String = "$eventType:$eventVersion"
}

private class EventParseException(
    val eventId: String?,
    val eventType: String?,
    val eventVersion: Int?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
