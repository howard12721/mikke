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

data class RedisStreamAppendResult(
    val appended: Boolean,
    val messageId: String?,
)

class RedisStreamProducer(
    private val commands: RedisCommands<String, String>,
    private val streamName: String,
) {
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
    private val streamName: String,
    val consumerGroup: String,
    private val consumerName: String,
) {
    private val staleClaimLock = Any()
    private var staleClaimCursor: String = "0-0"

    fun ensureGroup(startId: String = "0") {
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

    fun read(
        count: Long = 10,
        block: Duration = Duration.ofSeconds(1),
    ): List<RedisStreamRecord> =
        commands
            .xreadgroup(
                Consumer.from(consumerGroup, consumerName),
                XReadArgs.Builder.count(count).block(block.toMillis()),
                XReadArgs.StreamOffset.lastConsumed(streamName),
            ).map(::toRecord)

    fun ack(messageId: String): Long = commands.xack(streamName, consumerGroup, messageId)

    fun pendingSummary(): Long = commands.xpending(streamName, consumerGroup).count

    fun pendingMessages(count: Long = 10): List<PendingMessage> =
        commands.xpending(
            streamName,
            consumerGroup,
            Range.create("-", "+"),
            Limit.create(0, count),
        )

    fun claimStale(
        minIdle: Duration,
        count: Long = 10,
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
