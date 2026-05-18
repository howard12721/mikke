package jp.xhw.mikke.platform.uuid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.uuid.Uuid

class UuidBinaryTest {
    @Test
    fun `round trips UUID through binary16`() {
        val uuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

        val restored = uuid.toBinary16().toUuid()

        assertEquals(uuid, restored)
    }

    @Test
    fun `binary16 uses 16 bytes`() {
        val bytes = Uuid.random().toBinary16()

        assertEquals(16, bytes.size)
    }

    @Test
    fun `java UUID bridge round trips`() {
        val uuid = Uuid.random()

        assertEquals(uuid, uuid.toJavaUuid().toKotlinUuid())
    }
}
