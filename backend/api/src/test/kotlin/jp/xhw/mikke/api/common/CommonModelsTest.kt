package jp.xhw.mikke.api.common

import jp.xhw.mikke.api.common.application.requireUuidText
import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CommonModelsTest {
    @Test
    fun `requireUuidText accepts uuid text and trims it`() {
        assertEquals(
            "9254d717-ca67-4638-a48d-f92b36699061",
            " 9254d717-ca67-4638-a48d-f92b36699061 ".requireUuidText("mediaId"),
        )
    }

    @Test
    fun `requireUuidText rejects non uuid text as invalid request`() {
        val exception =
            assertThrows(ApiHttpException::class.java) {
                "not-a-media-id".requireUuidText("mediaId")
            }

        assertEquals(ApiErrorCode.InvalidRequest, exception.errorCode)
        assertEquals("mediaId must be a valid UUID", exception.message)
    }
}
