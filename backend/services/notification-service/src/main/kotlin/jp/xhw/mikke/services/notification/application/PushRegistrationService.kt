package jp.xhw.mikke.services.notification.application

import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.services.notification.model.PushPlatform
import jp.xhw.mikke.services.notification.model.PushRegistration
import jp.xhw.mikke.services.notification.model.StoredPushRegistration
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MAX_DEVICE_ID_LENGTH = 128
private const val MAX_INSTALLATION_ID_LENGTH = 4096

interface PushRegistrationRepository {
    fun upsert(registration: StoredPushRegistration): PushRegistration

    fun disable(
        userId: Uuid,
        deviceId: String,
        platform: PushPlatform,
        disabledAt: kotlin.time.Instant,
    )
}

data class RegisterPushInstallationCommand(
    val userId: Uuid,
    val deviceId: String,
    val platform: PushPlatform,
    val firebaseInstallationId: String,
)

data class DeletePushInstallationCommand(
    val userId: Uuid,
    val deviceId: String,
    val platform: PushPlatform,
)

class PushRegistrationService(
    private val repository: PushRegistrationRepository,
    private val transactionRunner: TransactionRunner,
    private val cipher: PushRegistrationCipher,
    private val clock: Clock = Clock.System,
) {
    fun register(command: RegisterPushInstallationCommand): PushRegistration {
        val deviceId = command.deviceId.validatedDeviceId()
        val installationId = command.firebaseInstallationId.validatedInstallationId()
        val now = clock.now()
        val registration =
            PushRegistration(
                id = Uuid.random(),
                userId = command.userId,
                deviceId = deviceId,
                platform = command.platform,
                enabled = true,
                createdAt = now,
                lastSeenAt = now,
            )

        return transactionRunner.runInTransaction {
            repository.upsert(
                StoredPushRegistration(
                    registration = registration,
                    registrationHash = cipher.hash(installationId),
                    encryptedInstallationId = cipher.encrypt(installationId),
                ),
            )
        }
    }

    fun delete(command: DeletePushInstallationCommand) {
        val deviceId = command.deviceId.validatedDeviceId()
        transactionRunner.runInTransaction {
            repository.disable(
                userId = command.userId,
                deviceId = deviceId,
                platform = command.platform,
                disabledAt = clock.now(),
            )
        }
    }
}

private fun String.validatedDeviceId(): String =
    trim().also { value ->
        if (value.isEmpty()) {
            throw ValidationException("device_id is required")
        }
        if (value.length > MAX_DEVICE_ID_LENGTH) {
            throw ValidationException("device_id must be at most $MAX_DEVICE_ID_LENGTH characters")
        }
    }

private fun String.validatedInstallationId(): String =
    trim().also { value ->
        if (value.isEmpty()) {
            throw ValidationException("firebase_installation_id is required")
        }
        if (value.length > MAX_INSTALLATION_ID_LENGTH) {
            throw ValidationException(
                "firebase_installation_id must be at most $MAX_INSTALLATION_ID_LENGTH characters",
            )
        }
    }
