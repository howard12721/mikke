package jp.xhw.mikke.api.common.application

import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException
import java.util.UUID

data class PageInput(
    val pageSize: Int?,
    val pageToken: String?,
)

data class PageResult<T>(
    val items: List<T>,
    val pageInfo: PageInfo,
)

data class PageInfo(
    val nextPageToken: String,
    val hasNextPage: Boolean,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

fun String.requireText(fieldName: String): String =
    trim().takeIf { it.isNotEmpty() }
        ?: throw ApiHttpException(
            status = ApiErrorCode.InvalidRequest.status,
            message = "$fieldName is required",
        )

fun String.requireUuidText(fieldName: String): String {
    val value = requireText(fieldName)
    try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw ApiHttpException(
            status = ApiErrorCode.InvalidRequest.status,
            message = "$fieldName must be a valid UUID",
        )
    }
    return value
}

fun PageInput.normalized(): PageInput =
    PageInput(
        pageSize = pageSize?.takeIf { it > 0 },
        pageToken = pageToken?.trim()?.takeIf { it.isNotEmpty() },
    )
