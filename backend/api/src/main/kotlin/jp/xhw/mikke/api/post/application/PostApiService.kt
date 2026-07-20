package jp.xhw.mikke.api.post.application

import jp.xhw.mikke.api.common.application.*
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.guess.application.GuessGateway
import jp.xhw.mikke.api.media.application.MediaGateway
import jp.xhw.mikke.api.user.application.UserGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class PostApiService(
    private val postGateway: PostGateway,
    private val mediaGateway: MediaGateway,
    private val userGateway: UserGateway,
    private val guessGateway: GuessGateway,
) {
    suspend fun createPost(
        context: ApiRequestContext,
        mediaId: String,
        caption: String?,
        location: GeoPoint,
        accuracyMeters: Double,
    ): Post =
        postGateway.createPost(
            context = context,
            mediaId = mediaId.requireUuidText("mediaId"),
            caption = caption?.trim()?.takeIf { it.isNotEmpty() },
            location = location,
            accuracyMeters = accuracyMeters,
        )

    suspend fun postDetail(
        context: ApiRequestContext,
        postId: String,
    ): TimelineItem {
        val post = postGateway.getPost(context, postId.requireText("postId"))
        return coroutineScope {
            val media = async { mediaGateway.getMedia(context, post.mediaId) }
            val author = async { userGateway.getUser(context, post.authorUserId) }
            val guessResult = async { guessGateway.getMyGuessForPost(context, post.id) }

            TimelineItem(
                post = post,
                author = author.await(),
                media = media.await(),
                myGuessResult = guessResult.await(),
            )
        }
    }

    suspend fun myPosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<TimelineItem> {
        val posts = postGateway.listMyPosts(context, page.normalized())
        return posts.toTimelineItems(context)
    }

    suspend fun userPosts(
        context: ApiRequestContext,
        userId: String,
        page: PageInput,
    ): PageResult<TimelineItem> {
        val posts = postGateway.listUserPosts(context, userId.requireText("userId"), page.normalized())
        return posts.toTimelineItems(context)
    }

    suspend fun timeline(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<TimelineItem> {
        val posts = postGateway.listVisiblePosts(context, page.normalized())
        return posts.toTimelineItems(context)
    }

    private suspend fun PageResult<Post>.toTimelineItems(context: ApiRequestContext): PageResult<TimelineItem> {
        val mediaById =
            mediaGateway
                .batchGetMedia(context, items.map { it.mediaId }.distinct())
                .associateBy { it.id }
        val usersById =
            userGateway
                .batchGetUsers(context, items.map { it.authorUserId }.distinct())
                .associateBy { it.id }
        val guessesByPostId =
            guessGateway
                .batchGetMyGuessesForPosts(context, items.map { it.id })
                .associateBy { it.guess.postId }

        return PageResult(
            items =
                items.map { post ->
                    TimelineItem(
                        post = post,
                        author = usersById[post.authorUserId],
                        media = mediaById[post.mediaId],
                        myGuessResult = guessesByPostId[post.id],
                    )
                },
            pageInfo = pageInfo,
        )
    }

    suspend fun updateCaption(
        context: ApiRequestContext,
        postId: String,
        caption: String,
    ): Post = postGateway.updateCaption(context, postId.requireText("postId"), caption.trim())

    suspend fun deletePost(
        context: ApiRequestContext,
        postId: String,
    ) = postGateway.deletePost(context, postId.requireText("postId"))
}
