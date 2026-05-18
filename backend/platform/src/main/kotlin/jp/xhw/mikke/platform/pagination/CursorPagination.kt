package jp.xhw.mikke.platform.pagination

import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.time.epochMicrosToInstant
import jp.xhw.mikke.platform.time.toEpochMicros
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface PaginationCursor

data class CreatedAtIdCursor(
    val createdAt: Instant,
    val id: Uuid,
) : PaginationCursor {
    companion object {
        private const val VERSION = "1"
        private const val SEPARATOR = ":"

        @OptIn(ExperimentalEncodingApi::class)
        fun encode(cursor: CreatedAtIdCursor): String {
            val payload = "${cursor.createdAt.toEpochMicros()}$SEPARATOR${formatGrpcUuid(cursor.id)}"
            val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray())
            return "$VERSION.$encoded"
        }

        @OptIn(ExperimentalEncodingApi::class)
        fun decode(token: String): CreatedAtIdCursor {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) {
                throw ValidationException("page_token must not be blank")
            }

            val versionSeparator = trimmed.indexOf('.')
            if (versionSeparator <= 0) {
                throw ValidationException("page_token is invalid")
            }

            val version = trimmed.substring(0, versionSeparator)
            if (version != VERSION) {
                throw ValidationException("page_token version is not supported")
            }

            val encoded = trimmed.substring(versionSeparator + 1)
            val payload =
                runCatching {
                    Base64.UrlSafe.decode(encoded).decodeToString()
                }.getOrElse {
                    throw ValidationException("page_token is invalid")
                }

            val separatorIndex = payload.indexOf(SEPARATOR)
            if (separatorIndex <= 0 || separatorIndex == payload.lastIndex) {
                throw ValidationException("page_token is invalid")
            }

            val createdAtMicros =
                payload
                    .substring(0, separatorIndex)
                    .toLongOrNull()
                    ?: throw ValidationException("page_token is invalid")

            val id =
                parseGrpcUuid(
                    raw = payload.substring(separatorIndex + 1),
                    fieldName = "page_token.id",
                )

            return CreatedAtIdCursor(
                createdAt = epochMicrosToInstant(createdAtMicros),
                id = id,
            )
        }
    }
}

data class PageSlice<T>(
    val items: List<T>,
    val nextPageToken: String?,
    val hasNextPage: Boolean,
)

fun <T> buildPageSlice(
    items: List<T>,
    limit: Int,
    nextCursor: CreatedAtIdCursor?,
): PageSlice<T> {
    val hasNextPage = items.size > limit
    val pageItems = if (hasNextPage) items.take(limit) else items
    val nextPageToken = if (hasNextPage && nextCursor != null) CreatedAtIdCursor.encode(nextCursor) else null

    return PageSlice(
        items = pageItems,
        nextPageToken = nextPageToken,
        hasNextPage = hasNextPage,
    )
}
