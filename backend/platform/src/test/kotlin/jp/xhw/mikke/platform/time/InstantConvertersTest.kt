package jp.xhw.mikke.platform.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class InstantConvertersTest {
    @Test
    fun `protobuf timestamp round trips`() {
        val instant = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)

        val restored = instant.toProtoTimestamp().toKotlinInstant()

        assertEquals(instant, restored)
    }

    @Test
    fun `epoch micros round trips`() {
        val instant = Instant.fromEpochSeconds(1_700_000_000, 123_456_000)

        val restored = epochMicrosToInstant(instant.toEpochMicros())

        assertEquals(instant, restored)
    }
}
