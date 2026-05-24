package jp.xhw.mikke.platform.events.subscription

import jp.xhw.mikke.platform.redis.RedisStreamProducer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val rawFieldsJson = Json

internal fun encodeDeadLetterRawFields(rawFields: Map<String, String>): String =
    rawFieldsJson.encodeToString(
        buildJsonObject {
            rawFields.toSortedMap().forEach { (key, value) ->
                put(key, value)
            }
        },
    )

class RedisDeadLetterSink(
    private val producer: RedisStreamProducer,
) : DeadLetterSink {
    override fun write(deadLetter: DeadLetterEvent) {
        producer.append(deadLetter.toRedisFields())
    }
}

private fun DeadLetterEvent.toRedisFields(): Map<String, String> =
    linkedMapOf(
        "original_stream_name" to originalStreamName,
        "original_message_id" to originalMessageId,
        "consumer_group" to consumerGroup,
        "consumer_name" to consumerName,
        "failure_category" to failureCategory.name,
        "failure_message" to failureMessage,
        "failed_at" to failedAt.toString(),
        "raw_fields" to encodeDeadLetterRawFields(rawFields),
    ).apply {
        eventId?.let { put("event_id", it) }
        eventType?.let { put("event_type", it) }
        eventVersion?.let { put("event_version", it.toString()) }
    }
