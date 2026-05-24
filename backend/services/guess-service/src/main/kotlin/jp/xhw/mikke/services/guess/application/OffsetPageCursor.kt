package jp.xhw.mikke.services.guess.application

import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.pagination.DEFAULT_PAGE_SIZE
import jp.xhw.mikke.platform.pagination.MAX_PAGE_SIZE
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.PaginationCursor
import jp.xhw.mikke.platform.pagination.ValidatedPageRequest
import jp.xhw.mikke.platform.pagination.validate
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class OffsetPageCursor(
    val offset: Int,
) : PaginationCursor {
    companion object {
        private const val VERSION = "1"

        @OptIn(ExperimentalEncodingApi::class)
        fun encode(offset: Int): String {
            require(offset >= 0) { "offset must be non-negative" }
            val encoded = Base64.UrlSafe.encode(offset.toString().encodeToByteArray())
            return "$VERSION.$encoded"
        }

        @OptIn(ExperimentalEncodingApi::class)
        fun decode(token: String): OffsetPageCursor {
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

            val offset =
                payload.toIntOrNull()
                    ?: throw ValidationException("page_token is invalid")
            if (offset < 0) {
                throw ValidationException("page_token is invalid")
            }

            return OffsetPageCursor(offset = offset)
        }
    }
}

fun PageRequestInput.validateOffset(
    defaultLimit: Int = DEFAULT_PAGE_SIZE,
    maxLimit: Int = MAX_PAGE_SIZE,
): ValidatedPageRequest<OffsetPageCursor> =
    validate(
        defaultLimit = defaultLimit,
        maxLimit = maxLimit,
        cursorDecoder = OffsetPageCursor::decode,
    )
