package jp.xhw.mikke.platform.events

import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

class ProcessedEventMarkResultTest {
    @Test
    fun `detects duplicate key as unique constraint violation`() {
        val exception = SQLException("duplicate", "23000", 1062)

        assertTrue(exception.isUniqueConstraintViolation())
    }

    @Test
    fun `processed event mark result variants`() {
        assertEquals(ProcessedEventMarkResult.Recorded, ProcessedEventMarkResult.Recorded)
        assertEquals(ProcessedEventMarkResult.AlreadyProcessed, ProcessedEventMarkResult.AlreadyProcessed)
    }
}
