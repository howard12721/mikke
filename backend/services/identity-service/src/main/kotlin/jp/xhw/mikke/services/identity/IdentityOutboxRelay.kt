package jp.xhw.mikke.services.identity

import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.outbox.RedisOutboxPublisher
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.services.identity.infrastructure.outbox.IdentityOutboxTable
import java.net.InetAddress
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

fun startIdentityOutboxRelay(transactionRunner: TransactionRunner) {
    val redis = connectRedisFromEnv()
    val streamName = System.getenv("IDENTITY_EVENTS_STREAM") ?: "mikke.identity.events"
    val publisherId = "identity-service-${hostname()}-${UUID.randomUUID()}"
    val publisher =
        RedisOutboxPublisher(
            outboxTable = IdentityOutboxTable,
            transactionRunner = transactionRunner,
            producer = RedisStreamProducer(redis.connection.sync(), streamName),
            producerName = "identity-service",
            publisherId = publisherId,
            batchSize = System.getenv("IDENTITY_OUTBOX_BATCH_SIZE")?.toIntOrNull() ?: 100,
        )
    val interval =
        (System.getenv("IDENTITY_OUTBOX_PUBLISH_INTERVAL_SECONDS")?.toLongOrNull() ?: 1)
            .coerceAtLeast(1)
            .seconds

    val relayThread =
        thread(
            name = "identity-outbox-relay",
            isDaemon = true,
        ) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    publisher.publishBatch()
                } catch (e: Exception) {
                    System.err.println("identity outbox relay publish failed: ${e.message ?: e::class.qualifiedName}")
                    e.printStackTrace(System.err)
                }

                try {
                    Thread.sleep(interval.inWholeMilliseconds)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            relayThread.interrupt()
            redis.close()
        },
    )
}

private fun hostname(): String =
    runCatching { InetAddress.getLocalHost().hostName }
        .getOrDefault("unknown")
