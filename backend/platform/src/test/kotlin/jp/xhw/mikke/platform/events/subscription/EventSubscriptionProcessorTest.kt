package jp.xhw.mikke.platform.events.subscription

import jp.xhw.mikke.platform.events.EventHandler
import jp.xhw.mikke.platform.redis.RedisStreamConsumerOperations
import jp.xhw.mikke.platform.redis.RedisStreamRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class EventSubscriptionProcessorTest {
    @Test
    fun `successful handler acks record`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val subscription = subscription(calls = calls)

            val outcome =
                subscription.processRecord(
                    validRecord(
                        eventType = TEST_EVENT_TYPE,
                        payloadJson = """{"value":"ok"}""",
                    ),
                )

            assertEquals(EventProcessOutcome.Ack, outcome)
            assertEquals(1, calls.get())
            assertTrue(deadLetters.isEmpty())
        }

    @Test
    fun `malformed record is dead-lettered and acked`() =
        runBlocking {
            val subscription = subscription()

            val outcome =
                subscription.processRecord(
                    RedisStreamRecord(
                        id = "1-0",
                        fields = mapOf("event_type" to TEST_EVENT_TYPE),
                    ),
                )

            assertEquals(EventProcessOutcome.Ack, outcome)
            assertEquals(1, deadLetters.size)
            assertEquals(DeadLetterFailureCategory.MALFORMED, deadLetters.single().failureCategory)
        }

    @Test
    fun `unsupported event type is dead-lettered and acked`() =
        runBlocking {
            val subscription = subscription()

            val outcome =
                subscription.processRecord(
                    validRecord(
                        eventType = "post.created",
                        payloadJson = """{"value":"ok"}""",
                    ),
                )

            assertEquals(EventProcessOutcome.Ack, outcome)
            assertEquals(DeadLetterFailureCategory.UNSUPPORTED_EVENT_TYPE, deadLetters.single().failureCategory)
        }

    @Test
    fun `ignored event type is acked without dead-letter`() =
        runBlocking {
            val subscription =
                subscription(
                    ignoredEventTypes = setOf("post.caption_updated"),
                )

            val outcome =
                subscription.processRecord(
                    validRecord(
                        eventType = "post.caption_updated",
                        payloadJson = """{"post_id":"00000000-0000-4000-8000-000000000003"}""",
                    ),
                )

            assertEquals(EventProcessOutcome.Ack, outcome)
            assertTrue(deadLetters.isEmpty())
        }

    @Test
    fun `unsupported event version is dead-lettered and acked`() =
        runBlocking {
            val subscription = subscription()

            val outcome =
                subscription.processRecord(
                    validRecord(
                        eventType = TEST_EVENT_TYPE,
                        eventVersion = 2,
                        payloadJson = """{"value":"ok"}""",
                    ),
                )

            assertEquals(EventProcessOutcome.Ack, outcome)
            assertEquals(DeadLetterFailureCategory.UNSUPPORTED_EVENT_VERSION, deadLetters.single().failureCategory)
        }

    @Test
    fun `handler failure below retry limit does not query delivery count when prefetched`() =
        runBlocking {
            val consumer = FakeConsumerGroup(deliveryCount = 1)
            val subscription =
                subscription(
                    handler = EventHandler<TestPayload> { throw IllegalStateException("boom") },
                    consumer = consumer,
                    maxDeliveryAttempts = 10,
                )

            val outcome =
                subscription.processRecord(
                    validRecord(
                        eventType = TEST_EVENT_TYPE,
                        payloadJson = """{"value":"ok"}""",
                    ),
                    deliveryCount = 1,
                )

            assertEquals(EventProcessOutcome.DoNotAck, outcome)
            assertEquals(0, consumer.deliveryCountQueries)
        }

    @Test
    fun `handler failure below retry limit does not ack`() =
        runBlocking {
            val subscription =
                subscription(
                    handler = EventHandler<TestPayload> { throw IllegalStateException("boom") },
                    deliveryCount = 1,
                )

            val outcome =
                subscription.processRecord(
                    validRecord(
                        eventType = TEST_EVENT_TYPE,
                        payloadJson = """{"value":"ok"}""",
                    ),
                )

            assertEquals(EventProcessOutcome.DoNotAck, outcome)
            assertTrue(deadLetters.isEmpty())
        }

    @Test
    fun `handler failure at retry limit is dead-lettered and acked`() =
        runBlocking {
            val subscription =
                subscription(
                    handler = EventHandler<TestPayload> { throw IllegalStateException("boom") },
                    deliveryCount = 10,
                    maxDeliveryAttempts = 10,
                )

            val outcome =
                subscription.processRecord(
                    validRecord(
                        eventType = TEST_EVENT_TYPE,
                        payloadJson = """{"value":"ok"}""",
                    ),
                )

            assertEquals(EventProcessOutcome.Ack, outcome)
            assertEquals(DeadLetterFailureCategory.HANDLER_FAILED, deadLetters.single().failureCategory)
        }

    private fun subscription(
        handler: EventHandler<TestPayload> = EventHandler { },
        calls: AtomicInteger = AtomicInteger(0),
        deliveryCount: Long = 1,
        consumer: FakeConsumerGroup = FakeConsumerGroup(deliveryCount),
        maxDeliveryAttempts: Int = 10,
        ignoredEventTypes: Set<String> = emptySet(),
    ): RedisEventSubscription {
        deadLetters.clear()
        return RedisEventSubscription(
            consumerGroup = consumer,
            ignoredEventTypes = ignoredEventTypes,
            handlers =
                listOf(
                    EventHandlerRegistration(
                        eventType = TEST_EVENT_TYPE,
                        eventVersion = 1,
                        payloadSerializer = TestPayload.serializer(),
                        handler =
                            EventHandler { event ->
                                calls.incrementAndGet()
                                handler.handle(event)
                            },
                    ),
                ),
            deadLetterSink = RecordingDeadLetterSink(deadLetters),
            maxDeliveryAttempts = maxDeliveryAttempts,
        )
    }

    private fun validRecord(
        eventType: String,
        payloadJson: String,
        eventVersion: Int = 1,
    ): RedisStreamRecord =
        RedisStreamRecord(
            id = "1-0",
            fields =
                mapOf(
                    "event_id" to "00000000-0000-4000-8000-000000000001",
                    "event_type" to eventType,
                    "event_version" to eventVersion.toString(),
                    "occurred_at" to "2026-05-24T01:02:03Z",
                    "producer" to "test-service",
                    "aggregate_type" to "test",
                    "aggregate_id" to "00000000-0000-4000-8000-000000000002",
                    "payload" to payloadJson,
                ),
        )

    private companion object {
        const val TEST_EVENT_TYPE = "test.created"

        val deadLetters = mutableListOf<DeadLetterEvent>()
    }
}

@Serializable
private data class TestPayload(
    val value: String,
)

private class FakeConsumerGroup(
    private val deliveryCount: Long,
) : RedisStreamConsumerOperations {
    var deliveryCountQueries = 0
        private set

    override val streamName: String = "mikke.test.events"

    override val consumerGroup: String = "test-consumer-group"

    override val consumerName: String = "test-consumer"

    override fun ensureGroup(startId: String) = Unit

    override fun read(
        count: Long,
        block: Duration,
    ): List<RedisStreamRecord> = emptyList()

    override fun ack(messageId: String): Long = 1

    override fun claimStale(
        minIdle: Duration,
        count: Long,
    ): List<RedisStreamRecord> = emptyList()

    override fun deliveryCount(messageId: String): Long {
        deliveryCountQueries += 1
        return deliveryCount
    }

    override fun deliveryCounts(messageIds: Collection<String>): Map<String, Long> = messageIds.distinct().associateWith { deliveryCount }
}

private class RecordingDeadLetterSink(
    private val deadLetters: MutableList<DeadLetterEvent>,
) : DeadLetterSink {
    override fun write(deadLetter: DeadLetterEvent) {
        deadLetters += deadLetter
    }
}
