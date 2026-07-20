package jp.xhw.mikke.services.post

import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.common.v1.PageInfo
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.grpc.requireInternalCaller
import jp.xhw.mikke.platform.grpc.requireUserUuid
import jp.xhw.mikke.platform.grpc.withGrpcExceptionMapping
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.post.v1.*
import jp.xhw.mikke.services.post.application.CreatePostCommand
import jp.xhw.mikke.services.post.application.PostService
import jp.xhw.mikke.services.post.model.MediaId
import jp.xhw.mikke.services.post.model.PostId
import jp.xhw.mikke.services.post.model.PostLocation
import jp.xhw.mikke.services.post.model.UserId
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("post-service")

class PostServiceRpc(
    private val postService: PostService,
) : PostServiceGrpcKt.PostServiceCoroutineImplBase() {
    override suspend fun createPost(request: CreatePostRequest): CreatePostResponse {
        val authorUserId = request.actor.toUserId()
        val mediaId = parseGrpcUuid(request.mediaId, "media_id").let(::MediaId)

        val post =
            mapRpcExceptions {
                if (!request.hasLocation()) {
                    throw ValidationException("location is required")
                }

                postService.createPost(
                    CreatePostCommand(
                        authorUserId = authorUserId,
                        mediaId = mediaId,
                        caption = request.caption,
                        location =
                            PostLocation(
                                latitude = request.location.latitude,
                                longitude = request.location.longitude,
                                accuracyMeters = request.accuracyMeters,
                            ),
                    ),
                )
            }

        return CreatePostResponse
            .newBuilder()
            .setPost(post.toProto())
            .build()
    }

    override suspend fun getPost(request: GetPostRequest): GetPostResponse {
        val viewerUserId = request.actor.toUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        val post =
            mapRpcExceptions {
                postService.getPost(postId = postId, viewerUserId = viewerUserId)
            }

        return GetPostResponse
            .newBuilder()
            .setPost(post.toProto())
            .build()
    }

    override suspend fun batchGetPosts(request: BatchGetPostsRequest): BatchGetPostsResponse {
        val viewerUserId = request.actor.toUserId()
        val postIds = request.postIdsList.map { parseGrpcUuid(it, "post_id").let(::PostId) }

        val posts =
            mapRpcExceptions {
                postService.batchGetPosts(postIds = postIds, viewerUserId = viewerUserId)
            }

        return BatchGetPostsResponse
            .newBuilder()
            .addAllPosts(posts.map { it.toProto() })
            .build()
    }

    override suspend fun listVisiblePosts(request: ListVisiblePostsRequest): ListVisiblePostsResponse {
        val viewerUserId = request.actor.toUserId()
        val page =
            PageRequestInput(
                pageSize = request.page.pageSize,
                pageToken = request.page.pageToken,
            ).validate()

        val slice =
            mapRpcExceptions {
                postService.listVisiblePosts(
                    viewerUserId = viewerUserId,
                    limit = page.limit,
                    cursor = page.cursor,
                )
            }

        return ListVisiblePostsResponse
            .newBuilder()
            .addAllPosts(slice.items.map { it.toProto() })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(slice.nextPageToken.orEmpty())
                    .setHasNextPage(slice.hasNextPage)
                    .build(),
            ).build()
    }

    override suspend fun listUserPosts(request: ListUserPostsRequest): ListUserPostsResponse {
        val viewerUserId = request.actor.toUserId()
        val authorUserId = parseGrpcUuid(request.userId, "user_id").let(::UserId)
        val page =
            PageRequestInput(
                pageSize = request.page.pageSize,
                pageToken = request.page.pageToken,
            ).validate()

        val slice =
            mapRpcExceptions {
                postService.listUserPosts(
                    authorUserId = authorUserId,
                    viewerUserId = viewerUserId,
                    limit = page.limit,
                    cursor = page.cursor,
                )
            }

        return ListUserPostsResponse
            .newBuilder()
            .addAllPosts(slice.items.map { it.toProto() })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(slice.nextPageToken.orEmpty())
                    .setHasNextPage(slice.hasNextPage)
                    .build(),
            ).build()
    }

    override suspend fun listMyPosts(request: ListMyPostsRequest): ListMyPostsResponse {
        val viewerUserId = request.actor.toUserId()
        val page =
            PageRequestInput(
                pageSize = request.page.pageSize,
                pageToken = request.page.pageToken,
            ).validate()

        val slice =
            mapRpcExceptions {
                postService.listMyPosts(
                    viewerUserId = viewerUserId,
                    limit = page.limit,
                    cursor = page.cursor,
                )
            }

        return ListMyPostsResponse
            .newBuilder()
            .addAllPosts(slice.items.map { it.toProto() })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(slice.nextPageToken.orEmpty())
                    .setHasNextPage(slice.hasNextPage)
                    .build(),
            ).build()
    }

    override suspend fun deletePost(request: DeletePostRequest): DeletePostResponse {
        val authorUserId = request.actor.toUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        mapRpcExceptions {
            postService.deletePost(postId = postId, authorUserId = authorUserId)
        }

        return DeletePostResponse.getDefaultInstance()
    }

    override suspend fun updatePostCaption(request: UpdatePostCaptionRequest): UpdatePostCaptionResponse {
        val authorUserId = request.actor.toUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        val post =
            mapRpcExceptions {
                postService.updatePostCaption(
                    postId = postId,
                    authorUserId = authorUserId,
                    caption = request.caption,
                )
            }

        return UpdatePostCaptionResponse
            .newBuilder()
            .setPost(post.toProto())
            .build()
    }

    override suspend fun checkPostVisibility(request: CheckPostVisibilityRequest): CheckPostVisibilityResponse {
        val viewerUserId = request.actor.toUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        val result =
            mapRpcExceptions {
                postService.checkPostVisibility(postId = postId, viewerUserId = viewerUserId)
            }

        val builder =
            CheckPostVisibilityResponse
                .newBuilder()
                .setCanView(result.canView)
        result.post?.let { builder.setPost(it.toProto()) }
        return builder.build()
    }

    override suspend fun getPostLocationForGuess(request: GetPostLocationForGuessRequest): GetPostLocationForGuessResponse {
        requireInternalCaller(setOf("guess-service"))
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        val location =
            mapRpcExceptions {
                postService.getPostLocationForGuess(postId)
            }

        return GetPostLocationForGuessResponse
            .newBuilder()
            .setPostId(location.postId.value.toString())
            .setAuthorUserId(location.authorUserId.value.toString())
            .setLocation(location.location.toProto())
            .build()
    }

    private fun ActorContext.toUserId(): UserId = UserId(requireUserUuid())
}

private suspend inline fun <T> mapRpcExceptions(crossinline block: suspend () -> T): T =
    withGrpcExceptionMapping(
        logger = logger,
        serviceName = "post-service",
        internalErrorDescription = "Internal post service error",
        domainExceptionMapper = { throwable -> throwable.toPostGrpcStatus() },
    ) {
        block()
    }
