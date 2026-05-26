package jp.xhw.mikke.api.common.application

import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException

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

fun PageInput.normalized(): PageInput =
    PageInput(
        pageSize = pageSize?.takeIf { it > 0 },
        pageToken = pageToken?.trim()?.takeIf { it.isNotEmpty() },
    )
