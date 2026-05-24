package jp.xhw.mikke.platform.redis

import io.lettuce.core.*
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.api.sync.RedisStreamCommands
import io.lettuce.core.models.stream.PendingMessage
import java.time.Duration

data class RedisStreamRecord(
    val id: String,
    val fields: Map<String, String>,
)

interface RedisStreamConsumerOperations {
    val streamName: String
    val consumerGroup: String
    val consumerName: String

    fun ensureGroup(startId: String = "0")

    fun read(
        count: Long = 10,
        block: Duration = Duration.ofSeconds(1),
    ): List<RedisStreamRecord>

    fun ack(messageId: String): Long

    fun claimStale(
        minIdle: Duration,
        count: Long = 10,
    ): List<RedisStreamRecord>

    fun deliveryCount(messageId: String): Long

    fun deliveryCounts(messageIds: Collection<String>): Map<String, Long>
}

data class RedisStreamAppendResult(
    val appended: Boolean,
    val messageId: String?,
)

class RedisStreamProducer(
    private val commands: RedisCommands<String, String>,
    val streamName: String,
) {
    fun append(fields: Map<String, String>): String {
        require(fields.isNotEmpty()) { "fields must not be empty" }

        val args = mutableListOf<String>()
        fields.forEach { (key, value) ->
            args += key
            args += value
        }

        return commands.xadd(streamName, *args.toTypedArray())
    }

    private val dedupeKey = "$streamName:published-event-ids"

    fun appendDeduplicated(
        eventId: String,
        fields: Map<String, String>,
    ): RedisStreamAppendResult {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(fields.isNotEmpty()) { "fields must not be empty" }

        val args = mutableListOf(eventId)
        fields.forEach { (key, value) ->
            args += key
            args += value
        }

        val messageId =
            commands.eval<String>(
                DEDUPLICATED_XADD_SCRIPT,
                ScriptOutputType.VALUE,
                arrayOf(streamName, dedupeKey),
                *args.toTypedArray(),
            )

        return RedisStreamAppendResult(
            appended = !messageId.isNullOrEmpty(),
            messageId = messageId.takeUnless { it.isNullOrEmpty() },
        )
    }

    private companion object {
        private const val DEDUPLICATED_XADD_SCRIPT =
            """
            if redis.call('SADD', KEYS[2], ARGV[1]) == 1 then
              return redis.call('XADD', KEYS[1], '*', unpack(ARGV, 2))
            end
            return ''
            """
    }
}

class RedisStreamConsumerGroup(
    private val commands: RedisStreamCommands<String, String>,
    override val streamName: String,
    override val consumerGroup: String,
    override val consumerName: String,
) : RedisStreamConsumerOperations {
    private val staleClaimLock = Any()
    private var staleClaimCursor: String = "0-0"

    override fun ensureGroup(startId: String) {
        try {
            commands.xgroupCreate(
                XReadArgs.StreamOffset.from(streamName, startId),
                consumerGroup,
                XGroupCreateArgs().mkstream(true),
            )
        } catch (e: RedisCommandExecutionException) {
            if (!isBusyGroupException(e)) {
                throw e
            }
            // Group already exists.
        }
    }

    private fun isBusyGroupException(e: RedisCommandExecutionException): Boolean = e.message?.contains("BUSYGROUP") == true

    override fun read(
        count: Long,
        block: Duration,
    ): List<RedisStreamRecord> =
        commands
            .xreadgroup(
                Consumer.from(consumerGroup, consumerName),
                XReadArgs.Builder.count(count).block(block.toMillis()),
                XReadArgs.StreamOffset.lastConsumed(streamName),
            ).map(::toRecord)

    override fun ack(messageId: String): Long = commands.xack(streamName, consumerGroup, messageId)

    override fun deliveryCount(messageId: String): Long =
        commands
            .xpending(
                streamName,
                consumerGroup,
                Range.create(messageId, messageId),
                Limit.create(0, 1),
            ).firstOrNull()
            ?.redeliveryCount
            ?: 1L

    override fun deliveryCounts(messageIds: Collection<String>): Map<String, Long> {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) {
            return emptyMap()
        }
        if (ids.size == 1) {
            val messageId = ids.single()
            return mapOf(messageId to deliveryCount(messageId))
        }

        val sorted = ids.sorted()
        val pending =
            commands.xpending(
                streamName,
                consumerGroup,
                Range.create(sorted.first(), sorted.last()),
                Limit.create(0, maxOf(ids.size.toLong(), 100L)),
            )
        val countsById = pending.associate { it.id to it.redeliveryCount }
        return ids.associateWith { messageId -> countsById[messageId] ?: 1L }
    }

    fun pendingSummary(): Long = commands.xpending(streamName, consumerGroup).count

    fun pendingMessages(count: Long = 10): List<PendingMessage> =
        commands.xpending(
            streamName,
            consumerGroup,
            Range.create("-", "+"),
            Limit.create(0, count),
        )

    override fun claimStale(
        minIdle: Duration,
        count: Long,
    ): List<RedisStreamRecord> =
        synchronized(staleClaimLock) {
            val claimed =
                commands.xautoclaim(
                    streamName,
                    XAutoClaimArgs<String>()
                        .minIdleTime(minIdle)
                        .startId(staleClaimCursor)
                        .count(count)
                        .consumer(Consumer.from(consumerGroup, consumerName)),
                )

            staleClaimCursor = claimed.id

            claimed.messages.map(::toRecord)
        }
}

private fun toRecord(message: StreamMessage<String, String>): RedisStreamRecord =
    RedisStreamRecord(
        id = message.id,
        fields = message.body,
    )
