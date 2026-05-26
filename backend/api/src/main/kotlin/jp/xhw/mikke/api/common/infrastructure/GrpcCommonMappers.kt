package jp.xhw.mikke.api.common.infrastructure

import jp.xhw.mikke.api.common.application.GeoPoint
import jp.xhw.mikke.api.common.application.PageInfo
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.infrastructure.grpcGatewayCall
import jp.xhw.mikke.common.v1.PageRequest
import jp.xhw.mikke.common.v1.GeoPoint as ProtoGeoPoint
import jp.xhw.mikke.common.v1.PageInfo as ProtoPageInfo

internal suspend fun <T> call(block: suspend () -> T): T = grpcGatewayCall(block)

internal fun PageInput.toProto(): PageRequest {
    val builder = PageRequest.newBuilder()
    pageSize?.let(builder::setPageSize)
    pageToken?.let(builder::setPageToken)
    return builder.build()
}

internal fun ProtoPageInfo.toPageInfo(): PageInfo =
    PageInfo(
        nextPageToken = nextPageToken,
        hasNextPage = hasNextPage,
    )

internal fun ProtoGeoPoint.toGeoPoint(): GeoPoint = GeoPoint(latitude = latitude, longitude = longitude)

internal fun GeoPoint.toProto(): ProtoGeoPoint =
    ProtoGeoPoint
        .newBuilder()
        .setLatitude(latitude)
        .setLongitude(longitude)
        .build()
