package jp.xhw.mikke.services.post.model

import kotlin.time.Instant

enum class PostVisibility {
    FRIENDS,
}

enum class PostStatus {
    ACTIVE,
    DELETED,
}

data class PostLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

data class Post(
    val id: PostId,
    val authorUserId: UserId,
    val mediaId: MediaId,
    val caption: String,
    val visibility: PostVisibility,
    val status: PostStatus,
    val location: PostLocation,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)
