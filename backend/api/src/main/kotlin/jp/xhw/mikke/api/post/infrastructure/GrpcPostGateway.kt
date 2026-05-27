package jp.xhw.mikke.api.post.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.api.common.application.GeoPoint
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.common.infrastructure.call
import jp.xhw.mikke.api.common.infrastructure.toPageInfo
import jp.xhw.mikke.api.common.infrastructure.toProto
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.infrastructure.*
import jp.xhw.mikke.api.post.application.Post
import jp.xhw.mikke.api.post.application.PostGateway
import jp.xhw.mikke.post.v1.*
import jp.xhw.mikke.post.v1.Post as ProtoPost

class GrpcPostGateway(
    private val channel: ManagedChannel,
    private val stub: PostServiceGrpcKt.PostServiceCoroutineStub =
        PostServiceGrpcKt.PostServiceCoroutineStub(channel).withInternalAuth(),
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
            stub
                .createPost(
                    CreatePostRequest
                        .newBuilder()
                        .setMediaId(mediaId)
                        .setCaption(caption.orEmpty())
                        .setVisibility(visibility.toPostVisibility())
                        .setLocation(location.toProto())
                        .setAccuracyMeters(accuracyMeters)
                        .setActor(context.requireActorProto())
                        .build(),
                ).post
                .toPost()
        }

    override suspend fun getPost(
        context: ApiRequestContext,
        postId: String,
    ): Post =
        call {
            stub
                .getPost(
                    GetPostRequest
                        .newBuilder()
                        .setPostId(postId)
                        .setActor(context.requireActorProto())
                        .build(),
                ).post
                .toPost()
        }

    override suspend fun listVisiblePosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post> =
        call {
            val response =
                stub.listVisiblePosts(
                    ListVisiblePostsRequest
                        .newBuilder()
                        .setPage(page.toProto())
                        .setActor(context.requireActorProto())
                        .build(),
                )
            PageResult(response.postsList.map { it.toPost() }, response.pageInfo.toPageInfo())
        }

    override suspend fun listMyPosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post> =
        call {
            val response =
                stub.listMyPosts(
                    ListMyPostsRequest
                        .newBuilder()
                        .setPage(page.toProto())
                        .setActor(context.requireActorProto())
                        .build(),
                )
            PageResult(response.postsList.map { it.toPost() }, response.pageInfo.toPageInfo())
        }

    override suspend fun updateCaption(
        context: ApiRequestContext,
        postId: String,
        caption: String,
    ): Post =
        call {
            stub
                .updatePostCaption(
                    UpdatePostCaptionRequest
                        .newBuilder()
                        .setPostId(postId)
                        .setCaption(caption)
                        .setActor(context.requireActorProto())
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
            stub
                .updatePostVisibility(
                    UpdatePostVisibilityRequest
                        .newBuilder()
                        .setPostId(postId)
                        .setVisibility(visibility.toPostVisibility())
                        .setActor(context.requireActorProto())
                        .build(),
                ).post
                .toPost()
        }

    override suspend fun deletePost(
        context: ApiRequestContext,
        postId: String,
    ) {
        call {
            stub.deletePost(
                DeletePostRequest
                    .newBuilder()
                    .setPostId(postId)
                    .setActor(context.requireActorProto())
                    .build(),
            )
        }
    }

    override fun close() = closeChannel(channel)

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
