package jp.xhw.mikke.api.common.presentation.graphql

data class PageInput(
    val pageSize: Int? = null,
    val pageToken: String? = null,
)

data class PageInfo(
    val nextPageToken: String,
    val hasNextPage: Boolean,
)

data class GeoPointInput(
    val latitude: Double,
    val longitude: Double,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

fun PageInput?.toApplication(): jp.xhw.mikke.api.common.application.PageInput =
    jp.xhw.mikke.api.common.application
        .PageInput(pageSize = this?.pageSize, pageToken = this?.pageToken)

fun GeoPointInput.toApplication(): jp.xhw.mikke.api.common.application.GeoPoint =
    jp.xhw.mikke.api.common.application
        .GeoPoint(latitude = latitude, longitude = longitude)

fun jp.xhw.mikke.api.common.application.PageInfo.toGraphQl(): PageInfo = PageInfo(nextPageToken = nextPageToken, hasNextPage = hasNextPage)

fun jp.xhw.mikke.api.common.application.GeoPoint.toGraphQl(): GeoPoint = GeoPoint(latitude, longitude)
