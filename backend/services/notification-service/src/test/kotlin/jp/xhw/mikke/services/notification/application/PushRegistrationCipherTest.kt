package jp.xhw.mikke.services.notification.application

import jp.xhw.mikke.platform.grpc.FailedPreconditionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PushRegistrationCipherTest {
    private val cipher = PushRegistrationCipher.fromKeyBytes(ByteArray(32) { index -> index.toByte() })

    @Test
    fun `encrypts and decrypts a Firebase installation id`() {
        val installationId = "cZo5p5wJQd-1mikke-installation"

        val first = cipher.encrypt(installationId)
        val second = cipher.encrypt(installationId)

        assertEquals(installationId, cipher.decrypt(first))
        assertEquals(installationId, cipher.decrypt(second))
        assertNotEquals(first, second)
        assertEquals(cipher.hash(installationId), cipher.hash(installationId))
        assertEquals(64, cipher.hash(installationId).length)
    }

    @Test
    fun `rejects a tampered encrypted value`() {
        val encrypted = cipher.encrypt("installation-id")
        val tampered = encrypted.dropLast(2) + "aa"

        assertThrows(FailedPreconditionException::class.java) {
            cipher.decrypt(tampered)
        }
    }
}
