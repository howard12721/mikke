package jp.xhw.mikke.services.post

import io.grpc.Status
import jp.xhw.mikke.common.v1.PageInfo
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthContext
import jp.xhw.mikke.platform.grpc.*
import jp.xhw.mikke.platform.pagination.PageRequestInput
import jp.xhw.mikke.platform.pagination.validate
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.post.v1.*
import jp.xhw.mikke.services.post.application.*
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
        val authorUserId = requireAuthenticatedUserId()
        val mediaId = parseGrpcUuid(request.mediaId, "media_id").let(::MediaId)

        val post =
            mapRpcExceptions {
                postService.createPost(
                    CreatePostCommand(
                        authorUserId = authorUserId,
                        mediaId = mediaId,
                        caption = request.caption,
                        visibility = request.visibility.toDomain(),
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
        val viewerUserId = requireAuthenticatedUserId()
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
        val viewerUserId = requireAuthenticatedUserId()
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
        val viewerUserId = resolveViewerUserId(request.viewerUserId)
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
        val viewerUserId = requireAuthenticatedUserId()
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
        val viewerUserId = requireAuthenticatedUserId()
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
        val authorUserId = requireAuthenticatedUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        mapRpcExceptions {
            postService.deletePost(postId = postId, authorUserId = authorUserId)
        }

        return DeletePostResponse.getDefaultInstance()
    }

    override suspend fun updatePostCaption(request: UpdatePostCaptionRequest): UpdatePostCaptionResponse {
        val authorUserId = requireAuthenticatedUserId()
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

    override suspend fun updatePostVisibility(request: UpdatePostVisibilityRequest): UpdatePostVisibilityResponse {
        val authorUserId = requireAuthenticatedUserId()
        val postId = parseGrpcUuid(request.postId, "post_id").let(::PostId)

        val post =
            mapRpcExceptions {
                postService.updatePostVisibility(
                    postId = postId,
                    authorUserId = authorUserId,
                    visibility = request.visibility.toDomain(),
                )
            }

        return UpdatePostVisibilityResponse
            .newBuilder()
            .setPost(post.toProto())
            .build()
    }

    override suspend fun checkPostVisibility(request: CheckPostVisibilityRequest): CheckPostVisibilityResponse {
        val viewerUserId = requireAuthenticatedUserId()
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

    private fun requireAuthenticatedUserId(): UserId {
        val principal =
            GrpcAuthContext.currentPrincipal()
                ?: throw Status.UNAUTHENTICATED.withDescription("Authentication required").asRuntimeException()
        return parseGrpcUuid(principal.subject, "user_id").let(::UserId)
    }

    private fun resolveViewerUserId(requestViewerUserId: String): UserId {
        val trimmed = requestViewerUserId.trim()
        if (trimmed.isEmpty()) {
            return requireAuthenticatedUserId()
        }

        val viewerUserId = parseGrpcUuid(trimmed, "viewer_user_id").let(::UserId)
        val authenticatedUserId = requireAuthenticatedUserId()
        if (viewerUserId != authenticatedUserId) {
            throw PermissionDeniedException("viewer_user_id must match authenticated user").toStatus().asRuntimeException()
        }
        return viewerUserId
    }
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
