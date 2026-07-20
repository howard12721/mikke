package jp.xhw.mikke.services.post

import jp.xhw.mikke.common.v1.GeoPoint
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.time.toProtoTimestamp
import jp.xhw.mikke.post.v1.Post
import jp.xhw.mikke.post.v1.PostStatus
import jp.xhw.mikke.post.v1.PostVisibility
import jp.xhw.mikke.services.post.model.PostLocation
import jp.xhw.mikke.services.post.model.Post as DomainPost
import jp.xhw.mikke.services.post.model.PostStatus as DomainPostStatus
import jp.xhw.mikke.services.post.model.PostVisibility as DomainPostVisibility

fun DomainPost.toProto(): Post =
    Post
        .newBuilder()
        .setId(id.value.toString())
        .setAuthorUserId(authorUserId.value.toString())
        .setMediaId(mediaId.value.toString())
        .setCaption(caption)
        .setVisibility(visibility.toProto())
        .setStatus(status.toProto())
        .setCreatedAt(createdAt.toProtoTimestamp())
        .setUpdatedAt(updatedAt.toProtoTimestamp())
        .build()

fun PostLocation.toProto(): GeoPoint =
    GeoPoint
        .newBuilder()
        .setLatitude(latitude)
        .setLongitude(longitude)
        .build()

fun DomainPostVisibility.toProto(): PostVisibility =
    when (this) {
        DomainPostVisibility.FRIENDS -> PostVisibility.POST_VISIBILITY_FRIENDS
    }

fun DomainPostStatus.toProto(): PostStatus =
    when (this) {
        DomainPostStatus.ACTIVE -> PostStatus.POST_STATUS_ACTIVE
        DomainPostStatus.DELETED -> PostStatus.POST_STATUS_DELETED
    }

fun PostVisibility.toDomain(): DomainPostVisibility =
    when (this) {
        PostVisibility.POST_VISIBILITY_FRIENDS -> DomainPostVisibility.FRIENDS
        else -> throw ValidationException("visibility is required")
    }
