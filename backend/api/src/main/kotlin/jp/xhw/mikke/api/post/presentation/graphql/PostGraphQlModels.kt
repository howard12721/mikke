package jp.xhw.mikke.api.post.presentation.graphql

import jp.xhw.mikke.api.common.presentation.graphql.GeoPointInput
import jp.xhw.mikke.api.common.presentation.graphql.PageInfo
import jp.xhw.mikke.api.guess.presentation.graphql.GuessResult
import jp.xhw.mikke.api.guess.presentation.graphql.toGraphQl
import jp.xhw.mikke.api.media.presentation.graphql.Media
import jp.xhw.mikke.api.media.presentation.graphql.toGraphQl
import jp.xhw.mikke.api.user.presentation.graphql.User
import jp.xhw.mikke.api.user.presentation.graphql.toGraphQl

data class CreatePostInput(
    val mediaId: String,
    val caption: String? = null,
    val location: GeoPointInput,
    val accuracyMeters: Double,
)

data class UpdatePostCaptionInput(
    val postId: String,
    val caption: String,
)

data class DeletePostPayload(
    val success: Boolean,
)

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
    val author: User?,
    val media: Media?,
    val myGuessResult: GuessResult?,
)

data class TimelinePage(
    val items: List<TimelineItem>,
    val pageInfo: PageInfo,
)

fun jp.xhw.mikke.api.post.application.Post.toGraphQl(): Post =
    Post(
        id = id,
        authorUserId = authorUserId,
        mediaId = mediaId,
        caption = caption,
        visibility = visibility,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun jp.xhw.mikke.api.post.application.TimelineItem.toGraphQl(): TimelineItem =
    TimelineItem(
        post = post.toGraphQl(),
        author = author?.toGraphQl(),
        media = media?.toGraphQl(),
        myGuessResult = myGuessResult?.toGraphQl(),
    )
