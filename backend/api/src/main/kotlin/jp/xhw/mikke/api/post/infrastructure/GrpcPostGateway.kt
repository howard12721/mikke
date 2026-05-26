package jp.xhw.mikke.api.post.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.common.application.GeoPoint
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.common.infrastructure.call
import jp.xhw.mikke.api.common.infrastructure.toPageInfo
import jp.xhw.mikke.api.common.infrastructure.toProto
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.infrastructure.authHeaderInterceptor
import jp.xhw.mikke.api.infrastructure.closeChannel
import jp.xhw.mikke.api.infrastructure.gatewayChannelFromEnvironment
import jp.xhw.mikke.api.infrastructure.toIsoString
import jp.xhw.mikke.api.post.application.Post
import jp.xhw.mikke.api.post.application.PostGateway
import jp.xhw.mikke.post.v1.*
import jp.xhw.mikke.post.v1.Post as ProtoPost

class GrpcPostGateway(
    private val channel: ManagedChannel,
    private val stub: PostServiceGrpcKt.PostServiceCoroutineStub =
        PostServiceGrpcKt.PostServiceCoroutineStub(channel),
) : PostGateway {
    override suspend fun createPost(
        context: ApiRequestContext,
        mediaId: String,
        caption: String?,
        visibility: String,
        location: GeoPoint,
        accuracyMeters: Double,
    ): Post =
        call {
            context
                .stub()
                .createPost(
                    CreatePostRequest
                        .newBuilder()
                        .setMediaId(mediaId)
                        .setCaption(caption.orEmpty())
                        .setVisibility(visibility.toPostVisibility())
                        .setLocation(location.toProto())
                        .setAccuracyMeters(accuracyMeters)
                        .build(),
                ).post
                .toPost()
        }

    override suspend fun getPost(
        context: ApiRequestContext,
        postId: String,
    ): Post =
        call {
            context
                .stub()
                .getPost(GetPostRequest.newBuilder().setPostId(postId).build())
                .post
                .toPost()
        }

    override suspend fun listVisiblePosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post> =
        call {
            val response =
                context
                    .stub()
                    .listVisiblePosts(ListVisiblePostsRequest.newBuilder().setPage(page.toProto()).build())
            PageResult(response.postsList.map { it.toPost() }, response.pageInfo.toPageInfo())
        }

    override suspend fun listMyPosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post> =
        call {
            val response =
                context
                    .stub()
                    .listMyPosts(ListMyPostsRequest.newBuilder().setPage(page.toProto()).build())
            PageResult(response.postsList.map { it.toPost() }, response.pageInfo.toPageInfo())
        }

    override suspend fun updateCaption(
        context: ApiRequestContext,
        postId: String,
        caption: String,
    ): Post =
        call {
            context
                .stub()
                .updatePostCaption(
                    UpdatePostCaptionRequest
                        .newBuilder()
                        .setPostId(postId)
                        .setCaption(caption)
                        .build(),
                ).post
                .toPost()
        }

    override suspend fun updateVisibility(
        context: ApiRequestContext,
        postId: String,
        visibility: String,
    ): Post =
        call {
            context
                .stub()
                .updatePostVisibility(
                    UpdatePostVisibilityRequest
                        .newBuilder()
                        .setPostId(postId)
                        .setVisibility(visibility.toPostVisibility())
                        .build(),
                ).post
                .toPost()
        }

    override suspend fun deletePost(
        context: ApiRequestContext,
        postId: String,
    ) {
        call {
            context.stub().deletePost(DeletePostRequest.newBuilder().setPostId(postId).build())
        }
    }

    override fun close() = closeChannel(channel)

    private fun ApiRequestContext.stub(): PostServiceGrpcKt.PostServiceCoroutineStub =
        authHeaderInterceptor(this)?.let { stub.withInterceptors(it) } ?: stub

    companion object {
        fun fromEnvironment(): GrpcPostGateway =
            GrpcPostGateway(
                gatewayChannelFromEnvironment(
                    targetEnv = "POST_SERVICE_TARGET",
                    hostEnv = "POST_SERVICE_HOST",
                    portEnv = "POST_SERVICE_PORT",
                    defaultPort = 50053,
                ),
            )
    }
}

private fun ProtoPost.toPost(): Post =
    Post(
        id = id,
        authorUserId = authorUserId,
        mediaId = mediaId,
        caption = caption,
        visibility = visibility.name.removePrefix("POST_VISIBILITY_"),
        status = status.name.removePrefix("POST_STATUS_"),
        createdAt = createdAt.toIsoString(),
        updatedAt = updatedAt.toIsoString(),
    )

private fun String.toPostVisibility(): PostVisibility =
    when (uppercase()) {
        "FRIENDS", "POST_VISIBILITY_FRIENDS" -> PostVisibility.POST_VISIBILITY_FRIENDS
        "PRIVATE", "POST_VISIBILITY_PRIVATE" -> PostVisibility.POST_VISIBILITY_PRIVATE
        else -> PostVisibility.POST_VISIBILITY_UNSPECIFIED
    }
