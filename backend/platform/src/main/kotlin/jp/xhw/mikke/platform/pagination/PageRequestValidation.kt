package jp.xhw.mikke.platform.pagination

import jp.xhw.mikke.platform.grpc.ValidationException

data class PageRequestInput(
    val pageSize: Int,
    val pageToken: String? = null,
)

data class ValidatedPageRequest<C : PaginationCursor>(
    val limit: Int,
    val cursor: C?,
)

fun <C : PaginationCursor> PageRequestInput.validate(
    defaultLimit: Int = DEFAULT_PAGE_SIZE,
    maxLimit: Int = MAX_PAGE_SIZE,
    cursorDecoder: (String) -> C,
): ValidatedPageRequest<C> {
    val limit =
        when {
            pageSize == 0 -> defaultLimit
            pageSize < 0 -> throw ValidationException("page_size must be positive")
            pageSize > maxLimit -> throw ValidationException("page_size must be <= $maxLimit")
            else -> pageSize
        }

    val cursor = pageToken?.trim()?.takeIf { it.isNotEmpty() }?.let(cursorDecoder)

    return ValidatedPageRequest(limit = limit, cursor = cursor)
}

fun PageRequestInput.validate(
    defaultLimit: Int = DEFAULT_PAGE_SIZE,
    maxLimit: Int = MAX_PAGE_SIZE,
): ValidatedPageRequest<CreatedAtIdCursor> =
    validate(
        defaultLimit = defaultLimit,
        maxLimit = maxLimit,
        cursorDecoder = CreatedAtIdCursor.Companion::decode,
    )

const val DEFAULT_PAGE_SIZE = 20
const val MAX_PAGE_SIZE = 100
