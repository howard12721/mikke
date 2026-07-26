package jp.xhw.mikke.services.notification.worker

import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.services.notification.application.PushDeliveryRepository
import jp.xhw.mikke.services.notification.application.PushRegistrationCipher
import jp.xhw.mikke.services.notification.model.PushMessage
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
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

fun interface PushSender {
    suspend fun send(
        firebaseInstallationId: String,
        message: PushMessage,
    ): String
}

class InvalidPushRegistrationException(
    cause: Throwable,
) : RuntimeException("Firebase installation is no longer registered", cause)

class PushDeliveryWorker(
    private val workerId: String,
    private val repository: PushDeliveryRepository,
    private val transactionRunner: TransactionRunner,
    private val cipher: PushRegistrationCipher,
    private val sender: PushSender,
    private val batchSize: Int = 100,
    private val maxAttempts: Int = 10,
    private val leaseDuration: Duration = 30.seconds,
    private val idleDelay: Duration = 1.seconds,
    private val errorDelay: Duration = 5.seconds,
    private val clock: Clock = Clock.System,
    private val logger: Logger = Logger.getLogger(PushDeliveryWorker::class.java.name),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    init {
        require(workerId.isNotBlank()) { "workerId must not be blank" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            scope.launch {
                while (isActive) {
                    try {
                        val claimed = deliverBatch()
                        if (claimed == 0) delay(idleDelay)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        logger.log(Level.SEVERE, "Push delivery worker failed", exception)
                        delay(errorDelay)
                    }
                }
            }
    }

    @Synchronized
    fun stop() {
        val running = job ?: return
        job = null
        runBlocking {
            running.cancelAndJoin()
        }
    }

    private suspend fun deliverBatch(): Int {
        val now = clock.now()
        val deliveries =
            transactionRunner.runInTransaction {
                repository.claimReady(
                    workerId = workerId,
                    limit = batchSize,
                    now = now,
                    leaseUntil = now + leaseDuration,
                )
            }

        deliveries.forEach { delivery ->
            if (!delivery.registrationEnabled) {
                fail(
                    deliveryId = delivery.id,
                    attemptCount = delivery.attemptCount,
                    message = "Push registration is disabled",
                    permanent = true,
                )
                return@forEach
            }

            try {
                val installationId = cipher.decrypt(delivery.encryptedInstallationId)
                val providerMessageId = sender.send(installationId, delivery.message)
                transactionRunner.runInTransaction {
                    repository.markSent(
                        deliveryId = delivery.id,
                        workerId = workerId,
                        providerMessageId = providerMessageId,
                        sentAt = clock.now(),
                    )
                }
            } catch (exception: InvalidPushRegistrationException) {
                val failedAt = clock.now()
                transactionRunner.runInTransaction {
                    repository.disableRegistration(delivery.registrationId, failedAt)
                    repository.markFailed(
                        deliveryId = delivery.id,
                        workerId = workerId,
                        error = exception.message.orEmpty(),
                        retryAt = failedAt,
                        permanent = true,
                        failedAt = failedAt,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                fail(
                    deliveryId = delivery.id,
                    attemptCount = delivery.attemptCount,
                    message = exception.message ?: exception::class.qualifiedName.orEmpty(),
                    permanent = delivery.attemptCount >= maxAttempts,
                )
            }
        }

        return deliveries.size
    }

    private fun fail(
        deliveryId: kotlin.uuid.Uuid,
        attemptCount: Int,
        message: String,
        permanent: Boolean,
    ) {
        val failedAt = clock.now()
        val retryAt =
            if (permanent) {
                failedAt
            } else {
                failedAt + retryDelay(attemptCount)
            }
        transactionRunner.runInTransaction {
            repository.markFailed(
                deliveryId = deliveryId,
                workerId = workerId,
                error = message,
                retryAt = retryAt,
                permanent = permanent,
                failedAt = failedAt,
            )
        }
    }

    private fun retryDelay(attemptCount: Int): Duration {
        val seconds = min(2.0.pow((attemptCount - 1).coerceAtLeast(0)).toLong(), 1.hours.inWholeSeconds)
        return seconds.seconds
    }
}
