package jp.xhw.mikke.api.post.application

import jp.xhw.mikke.api.common.application.GeoPoint
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.guess.application.GuessResult
import jp.xhw.mikke.api.media.application.Media
import jp.xhw.mikke.api.user.application.PublicUser

data class Post(
    val id: String,
    val authorUserId: String,
    val mediaId: String,
    val caption: String,
    val visibility: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

data class TimelineItem(
    val post: Post,
    val author: PublicUser?,
    val media: Media?,
    val myGuessResult: GuessResult?,
)

interface PostGateway : AutoCloseable {
    suspend fun createPost(
        context: ApiRequestContext,
        mediaId: String,
        caption: String?,
        location: GeoPoint,
        accuracyMeters: Double,
    ): Post

    suspend fun getPost(
        context: ApiRequestContext,
        postId: String,
    ): Post

    suspend fun listVisiblePosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post>

    suspend fun listUserPosts(
        context: ApiRequestContext,
        userId: String,
        page: PageInput,
    ): PageResult<Post>

    suspend fun listMyPosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post>

    suspend fun updateCaption(
        context: ApiRequestContext,
        postId: String,
        caption: String,
    ): Post

    suspend fun deletePost(
        context: ApiRequestContext,
        postId: String,
    )
}
