package jp.xhw.mikke.platform.outbox

import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.outbox.exposed.OutboxTable
import jp.xhw.mikke.platform.outbox.exposed.claimForPublishing
import jp.xhw.mikke.platform.outbox.exposed.markPublishedByPublisher
import jp.xhw.mikke.platform.outbox.exposed.releasePublishClaim
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RedisOutboxPublisher(
    private val outboxTable: OutboxTable,
    private val transactionRunner: TransactionRunner,
    private val producer: RedisStreamProducer,
    private val producerName: String,
    private val publisherId: String,
    private val batchSize: Int = 100,
    private val leaseDuration: Duration = 30.seconds,
) {
    init {
        require(producerName.isNotBlank()) { "producerName must not be blank" }
        require(publisherId.isNotBlank()) { "publisherId must not be blank" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(leaseDuration.isPositive()) { "leaseDuration must be positive" }
    }

    fun publishBatch(): OutboxPublishBatchResult {
        val now = Clock.System.now()
        val claimed =
            transactionRunner.runInTransaction {
                outboxTable.claimForPublishing(
                    publisherId = publisherId,
                    limit = batchSize,
                    leaseUntil = now + leaseDuration,
                    now = now,
                )
            }

        var published = 0
        var duplicates = 0
        var failed = 0

        claimed.forEach { entry ->
            try {
                val result =
                    producer.appendDeduplicated(
                        eventId = entry.id.toString(),
                        fields = entry.toRedisStreamFields(producerName),
                    )

                if (result.appended) {
                    published += 1
                } else {
                    duplicates += 1
                }

                transactionRunner.runInTransaction {
                    outboxTable.markPublishedByPublisher(
                        id = entry.id,
                        publisherId = publisherId,
                        publishedAt = Clock.System.now(),
                    )
                }
            } catch (e: Exception) {
                failed += 1
                transactionRunner.runInTransaction {
                    outboxTable.releasePublishClaim(
                        id = entry.id,
                        publisherId = publisherId,
                        error = e.message ?: e::class.qualifiedName ?: "publish failed",
                    )
                }
            }
        }

        return OutboxPublishBatchResult(
            claimed = claimed.size,
            published = published,
            duplicates = duplicates,
            failed = failed,
        )
    }
}

data class OutboxPublishBatchResult(
    val claimed: Int,
    val published: Int,
    val duplicates: Int,
    val failed: Int,
)

fun OutboxEntry.toRedisStreamFields(producerName: String): Map<String, String> =
    linkedMapOf(
        "event_id" to id.toString(),
        "event_type" to eventType,
        "event_version" to eventVersion.toString(),
        "occurred_at" to createdAt.toString(),
        "producer" to producerName,
        "aggregate_type" to aggregateType,
        "aggregate_id" to aggregateId.toString(),
        "payload" to payloadJson,
    ).apply {
        correlationId?.let { put("correlation_id", it) }
        causationId?.let { put("causation_id", it.toString()) }
    }
