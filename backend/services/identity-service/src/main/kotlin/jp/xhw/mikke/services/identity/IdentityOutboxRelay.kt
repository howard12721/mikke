package jp.xhw.mikke.services.identity

import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.outbox.OutboxRelay
import jp.xhw.mikke.platform.outbox.RedisOutboxPublisher
import jp.xhw.mikke.platform.redis.RedisStreamProducer
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import jp.xhw.mikke.services.identity.infrastructure.outbox.IdentityOutboxTable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun startIdentityOutboxRelay(transactionRunner: TransactionRunner) {
    val redis = connectRedisFromEnv()
    val outboxRelay =
        OutboxRelay(
            publisher =
                RedisOutboxPublisher(
                    outboxTable = IdentityOutboxTable,
                    transactionRunner = transactionRunner,
                    producer =
                        RedisStreamProducer(
                            commands = redis.connection.sync(),
                            streamName = System.getenv("IDENTITY_EVENTS_STREAM") ?: "mikke.identity.events",
                        ),
                    producerName = "identity-service",
                    publisherId =
                        System.getenv("OUTBOX_PUBLISHER_ID") ?: "identity-service-${ProcessHandle.current().pid()}",
                    batchSize =
                        System.getenv("OUTBOX_RELAY_BATCH_SIZE")?.toIntOrNull()
                            ?: System.getenv("IDENTITY_OUTBOX_BATCH_SIZE")?.toIntOrNull()
                            ?: 100,
                    leaseDuration = System.getenv("OUTBOX_RELAY_LEASE_SECONDS")?.toLongOrNull()?.seconds ?: 30.seconds,
                ),
            idleDelay =
                System.getenv("OUTBOX_RELAY_IDLE_DELAY_MILLIS")?.toLongOrNull()?.milliseconds
                    ?: System.getenv("IDENTITY_OUTBOX_PUBLISH_INTERVAL_SECONDS")?.toLongOrNull()?.seconds
                    ?: 500.milliseconds,
            errorDelay = System.getenv("OUTBOX_RELAY_ERROR_DELAY_MILLIS")?.toLongOrNull()?.milliseconds ?: 5.seconds,
        )
    outboxRelay.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            outboxRelay.stop()
            redis.close()
        },
    )
}
