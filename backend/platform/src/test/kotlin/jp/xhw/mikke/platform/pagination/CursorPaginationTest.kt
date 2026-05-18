package jp.xhw.mikke.platform.pagination

import jp.xhw.mikke.platform.grpc.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CursorPaginationTest {
    @Test
    fun `encodes and decodes created_at id cursor`() {
        val cursor =
            CreatedAtIdCursor(
                createdAt = Instant.fromEpochSeconds(1_700_000_000, 123_000_000),
                id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
            )

        val token = CreatedAtIdCursor.encode(cursor)
        val decoded = CreatedAtIdCursor.decode(token)

        assertEquals(cursor, decoded)
    }

    @Test
    fun `rejects invalid cursor token`() {
        assertThrows(ValidationException::class.java) {
            CreatedAtIdCursor.decode("not-a-valid-token")
        }
    }

    @Test
    fun `validates page request`() {
        val validated =
            PageRequestInput(pageSize = 0, pageToken = null).validate(defaultLimit = 25)

        assertEquals(25, validated.limit)
        assertEquals(null, validated.cursor)
    }

    @Test
    fun `builds next page token when extra item exists`() {
        val cursor =
            CreatedAtIdCursor(
                createdAt = Instant.fromEpochSeconds(10),
                id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
            )
        val slice =
            buildPageSlice(
                items = listOf("a", "b", "c"),
                limit = 2,
                nextCursor = cursor,
            )

        assertEquals(listOf("a", "b"), slice.items)
        assertEquals(true, slice.hasNextPage)
        assertEquals(CreatedAtIdCursor.encode(cursor), slice.nextPageToken)
    }
}
