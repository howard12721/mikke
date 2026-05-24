package jp.xhw.mikke.platform.events.subscription

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RedisDeadLetterSinkTest {
    @Test
    fun `raw_fields is deterministic sorted JSON`() {
        val encoded1 =
            encodeDeadLetterRawFields(
                linkedMapOf(
                    "z_field" to "1",
                    "a_field" to "2",
                    "m_field" to "3",
                ),
            )
        val encoded2 =
            encodeDeadLetterRawFields(
                mapOf(
                    "a_field" to "2",
                    "m_field" to "3",
                    "z_field" to "1",
                ),
            )

        assertEquals("""{"a_field":"2","m_field":"3","z_field":"1"}""", encoded1)
        assertEquals(encoded1, encoded2)
    }

    @Test
    fun `raw_fields JSON-escapes special characters`() {
        val encoded =
            encodeDeadLetterRawFields(
                mapOf(
                    "payload" to "line1\nline2 \"quoted\" \\ backslash & foo=bar %percent",
                ),
            )

        assertEquals(
            """{"payload":"line1\nline2 \"quoted\" \\ backslash & foo=bar %percent"}""",
            encoded,
        )
    }

    @Test
    fun `raw_fields encodes empty map as empty JSON object`() {
        assertEquals("{}", encodeDeadLetterRawFields(emptyMap()))
    }
}
