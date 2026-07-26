package jp.xhw.mikke.services.notification.application

import jp.xhw.mikke.services.notification.model.PushDelivery
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface PushDeliveryRepository {
    fun claimReady(
        workerId: String,
        limit: Int,
        now: Instant,
        leaseUntil: Instant,
    ): List<PushDelivery>

    fun markSent(
        deliveryId: Uuid,
        workerId: String,
        providerMessageId: String,
        sentAt: Instant,
    ): Boolean

    fun markFailed(
        deliveryId: Uuid,
        workerId: String,
        error: String,
        retryAt: Instant,
        permanent: Boolean,
        failedAt: Instant,
    ): Boolean

    fun disableRegistration(
        registrationId: Uuid,
        disabledAt: Instant,
    )
}
