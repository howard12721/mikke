package jp.xhw.mikke.platform.outbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class OutboxRelay(
    private val publisher: RedisOutboxPublisher,
    private val idleDelay: Duration = 1.seconds,
    private val errorDelay: Duration = 5.seconds,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job != null) {
            return
        }

        job =
            scope.launch {
                while (isActive) {
                    try {
                        val result = publisher.publishBatch()
                        if (result.claimed == 0) {
                            delay(idleDelay)
                        }
                    } catch (throwable: Throwable) {
                        println("outbox relay failed: ${throwable.message ?: throwable::class.qualifiedName}")
                        delay(errorDelay)
                    }
                }
            }
    }

    fun stop() {
        runBlocking {
            job?.cancelAndJoin()
            scope.cancel()
        }
    }
}
