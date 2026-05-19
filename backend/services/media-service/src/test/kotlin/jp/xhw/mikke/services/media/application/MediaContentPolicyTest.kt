package jp.xhw.mikke.services.media.application

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MediaContentPolicyTest {
    @Test
    fun `accepts allowed content types`() {
        assertDoesNotThrow { MediaContentPolicy.validateContentType("image/jpeg") }
        assertDoesNotThrow { MediaContentPolicy.validateContentType("image/png") }
        assertDoesNotThrow { MediaContentPolicy.validateContentType("image/webp") }
    }

    @Test
    fun `rejects unsupported content type`() {
        assertThrows(InvalidMediaInputException::class.java) {
            MediaContentPolicy.validateContentType("image/heic")
        }
    }

    @Test
    fun `rejects oversized content length`() {
        assertThrows(InvalidMediaInputException::class.java) {
            MediaContentPolicy.validateContentLength(MediaContentPolicy.MAX_CONTENT_LENGTH_BYTES + 1)
        }
    }
}
