package jp.xhw.mikke.platform.outbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class OutboxRelay(
    private val publisher: OutboxPublisher,
    private val idleDelay: Duration = 1.seconds,
    private val errorDelay: Duration = 5.seconds,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) {
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
                    } catch (throwable: CancellationException) {
                        throw throwable
                    } catch (throwable: Throwable) {
                        println("outbox relay failed: ${throwable.message ?: throwable::class.qualifiedName}")
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
}
