package jp.xhw.mikke.platform.outbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

class OutboxRelayTest {
    @Test
    fun `start is idempotent while running and relay can restart after stop`() {
        val calls = AtomicInteger(0)
        val firstRun = CountDownLatch(1)
        val secondRun = CountDownLatch(1)
        val publisher =
            OutboxPublisher {
                when (calls.incrementAndGet()) {
                    1 -> firstRun.countDown()
                    2 -> secondRun.countDown()
                }

                OutboxPublishBatchResult(
                    claimed = 0,
                    published = 0,
                    duplicates = 0,
                    failed = 0,
                )
            }
        val relay =
            OutboxRelay(
                publisher = publisher,
                idleDelay = 1.minutes,
                errorDelay = 1.minutes,
            )

        relay.start()
        assertTrue(firstRun.await(1, TimeUnit.SECONDS))

        relay.start()
        assertEquals(1, calls.get())

        relay.stop()
        relay.start()

        assertTrue(secondRun.await(1, TimeUnit.SECONDS))
        relay.stop()
    }
}
