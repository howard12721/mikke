package jp.xhw.mikke.platform.events.subscription

import jp.xhw.mikke.platform.redis.RedisStreamConsumerOperations
import jp.xhw.mikke.platform.redis.RedisStreamRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RedisEventSubscription(
    private val consumerGroup: RedisStreamConsumerOperations,
    private val handlers: List<EventHandlerRegistration<*>>,
    private val deadLetterSink: DeadLetterSink,
    private val ignoredEventTypes: Set<String> = emptySet(),
    private val startId: String = "$",
    private val maxDeliveryAttempts: Int = 10,
    private val readCount: Long = 10,
    private val staleMinIdle: Duration = Duration.ofMinutes(5),
    private val idleDelay: kotlin.time.Duration = 500.milliseconds,
    private val errorDelay: kotlin.time.Duration = 5.seconds,
    private val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    private val logger: Logger = Logger.getLogger(RedisEventSubscription::class.java.name),
) {
    private val processor =
        EventSubscriptionProcessor(
            consumerGroupName = consumerGroup.consumerGroup,
            consumerName = consumerGroup.consumerName,
            streamName = consumerGroup.streamName,
            handlers = handlers,
            deadLetterSink = deadLetterSink,
            deliveryCountProvider = consumerGroup::deliveryCount,
            maxDeliveryAttempts = maxDeliveryAttempts,
            ignoredEventTypes = ignoredEventTypes,
            clock = clock,
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    init {
        require(startId.isNotBlank()) { "startId must not be blank" }
        require(readCount > 0) { "readCount must be positive" }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) {
            return
        }

        consumerGroup.ensureGroup(startId = startId)
        job =
            scope.launch {
                while (isActive) {
                    try {
                        val claimed = consumerGroup.claimStale(minIdle = staleMinIdle, count = readCount)
                        val read = consumerGroup.read(count = readCount)
                        val records = (claimed + read).distinctBy { it.id }

                        if (records.isEmpty()) {
                            delay(idleDelay)
                        } else {
                            processAndAck(records)
                        }
                    } catch (throwable: CancellationException) {
                        throw throwable
                    } catch (throwable: Throwable) {
                        logger.log(Level.SEVERE, "Event subscription failed while processing records", throwable)
                        delay(errorDelay)
                    }
                }
            }
    }

    @Synchronized
    fun stop() {
        val runningJob = job ?: return
        job = null

        runBlocking {
            runningJob.cancelAndJoin()
        }
    }

    internal suspend fun processRecord(
        record: RedisStreamRecord,
        deliveryCount: Long? = null,
    ): EventProcessOutcome = processor.process(record, deliveryCount)

    internal suspend fun processAndAck(
        record: RedisStreamRecord,
        deliveryCount: Long? = null,
    ) {
        when (processor.process(record, deliveryCount)) {
            EventProcessOutcome.Ack -> consumerGroup.ack(record.id)
            EventProcessOutcome.DoNotAck -> Unit
        }
    }

    private suspend fun processAndAck(records: List<RedisStreamRecord>) {
        val distinct = records.distinctBy { it.id }
        val deliveryCounts = consumerGroup.deliveryCounts(distinct.map { it.id })
        distinct.forEach { record ->
            processAndAck(record, deliveryCounts[record.id])
        }
    }
}
