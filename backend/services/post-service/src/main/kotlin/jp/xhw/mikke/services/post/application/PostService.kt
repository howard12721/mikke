package jp.xhw.mikke.services.post.application

import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.grpc.AlreadyExistsException
import jp.xhw.mikke.platform.grpc.NotFoundException
import jp.xhw.mikke.platform.grpc.PermissionDeniedException
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.platform.pagination.PageSlice
import jp.xhw.mikke.platform.pagination.buildPageSlice
import jp.xhw.mikke.services.post.model.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MAX_CAPTION_LENGTH = 500
private const val MAX_ACCURACY_METERS = 1000.0
private const val AGGREGATE_TYPE = "post"
private const val PRODUCER = "post-service"

private val ALLOWED_MEDIA_CONTENT_TYPES =
    setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
    )

class PostService(
    private val postRepository: PostRepository,
    private val outboxRepository: PostOutboxRepository,
    private val mediaChecker: PostMediaChecker,
    private val visibilityAuthorizer: PostVisibilityAuthorizer,
    private val userStatusChecker: PostUserStatusChecker,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock = Clock.System,
) {
    suspend fun createPost(command: CreatePostCommand): Post {
        validateCaption(command.caption)
        validateLocation(command.location)
        userStatusChecker.requireActiveUser(command.authorUserId)

        val verifiedMedia =
            mediaChecker.verifyReadyMediaOwnedBy(
                mediaId = command.mediaId,
                ownerUserId = command.authorUserId,
            )
        if (verifiedMedia.contentType !in ALLOWED_MEDIA_CONTENT_TYPES) {
            throw ValidationException("media content type is not allowed for posts")
        }

        val now = clock.now()
        val post =
            Post(
                id = PostId(Uuid.random()),
                authorUserId = command.authorUserId,
                mediaId = command.mediaId,
                caption = command.caption,
                visibility = command.visibility,
                status = PostStatus.ACTIVE,
                location = command.location,
                createdAt = now,
                updatedAt = now,
            )

        return transactionRunner.runInTransaction {
            try {
                postRepository.save(post)
            } catch (_: DuplicatePostMediaException) {
                throw AlreadyExistsException("media is already attached to another post")
            }

            outboxRepository.append(
                OutboxEntry(
                    id = Uuid.random(),
                    eventType = PostEventTypes.CREATED,
                    aggregateType = AGGREGATE_TYPE,
                    aggregateId = post.id.value,
                    payloadJson =
                        encodePostEventPayload(
                            PostCreatedPayload(
                                postId = post.id.value.toString(),
                                authorUserId = post.authorUserId.value.toString(),
                                mediaId = post.mediaId.value.toString(),
                                visibility = post.visibility.name,
                                status = post.status.name,
                                createdAt = post.createdAt.toString(),
                            ),
                        ),
                    createdAt = now,
                ),
            )
            post
        }
    }

    suspend fun getPost(
        postId: PostId,
        viewerUserId: UserId,
    ): Post {
        val post =
            transactionRunner.runInTransaction {
                postRepository.findById(postId)
            } ?: throw NotFoundException("post not found")

        return authorizePostView(post, viewerUserId)
    }

    suspend fun batchGetPosts(
        postIds: List<PostId>,
        viewerUserId: UserId,
    ): List<Post> {
        if (postIds.isEmpty()) {
            return emptyList()
        }

        val posts =
            transactionRunner.runInTransaction {
                postRepository.findByIds(postIds)
            }

        return filterVisiblePosts(posts, viewerUserId)
    }

    suspend fun listVisiblePosts(
        viewerUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): PageSlice<Post> {
        userStatusChecker.requireActiveUser(viewerUserId)

        val visiblePosts = mutableListOf<Post>()
        var fetchCursor = cursor
        var lastFetched: Post?

        while (visiblePosts.size <= limit) {
            val candidates =
                transactionRunner.runInTransaction {
                    postRepository.listActiveFriendsPosts(limit = limit + 1, cursor = fetchCursor)
                }
            if (candidates.isEmpty()) {
                break
            }

            val filtered = filterVisiblePosts(candidates, viewerUserId)
            visiblePosts.addAll(filtered)
            lastFetched = candidates.last()

            if (candidates.size <= limit) {
                break
            }

            fetchCursor =
                CreatedAtIdCursor(
                    createdAt = lastFetched.createdAt,
                    id = lastFetched.id.value,
                )
        }

        val nextCursor =
            if (visiblePosts.size > limit) {
                val boundary = visiblePosts[limit - 1]
                CreatedAtIdCursor(createdAt = boundary.createdAt, id = boundary.id.value)
            } else {
                null
            }

        return buildPageSlice(
            items = visiblePosts,
            limit = limit,
            nextCursor = nextCursor,
        )
    }

    suspend fun listUserPosts(
        authorUserId: UserId,
        viewerUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): PageSlice<Post> {
        val visiblePosts = mutableListOf<Post>()
        var fetchCursor = cursor
        var lastFetched: Post?

        while (visiblePosts.size <= limit) {
            val candidates =
                transactionRunner.runInTransaction {
                    postRepository.listByAuthor(
                        authorUserId = authorUserId,
                        includeDeleted = false,
                        limit = limit + 1,
                        cursor = fetchCursor,
                    )
                }
            if (candidates.isEmpty()) {
                break
            }

            val filtered = filterVisiblePosts(candidates, viewerUserId)
            visiblePosts.addAll(filtered)
            lastFetched = candidates.last()

            if (candidates.size <= limit) {
                break
            }

            fetchCursor =
                CreatedAtIdCursor(
                    createdAt = lastFetched.createdAt,
                    id = lastFetched.id.value,
                )
        }

        val nextCursor =
            if (visiblePosts.size > limit) {
                val boundary = visiblePosts[limit - 1]
                CreatedAtIdCursor(createdAt = boundary.createdAt, id = boundary.id.value)
            } else {
                null
            }

        return buildPageSlice(
            items = visiblePosts,
            limit = limit,
            nextCursor = nextCursor,
        )
    }

    suspend fun listMyPosts(
        viewerUserId: UserId,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): PageSlice<Post> {
        userStatusChecker.requireActiveUser(viewerUserId)

        val posts =
            transactionRunner.runInTransaction {
                postRepository.listByAuthor(
                    authorUserId = viewerUserId,
                    includeDeleted = false,
                    limit = limit + 1,
                    cursor = cursor,
                )
            }

        val nextCursor =
            if (posts.size > limit) {
                val boundary = posts[limit - 1]
                CreatedAtIdCursor(createdAt = boundary.createdAt, id = boundary.id.value)
            } else {
                null
            }

        return buildPageSlice(
            items = posts,
            limit = limit,
            nextCursor = nextCursor,
        )
    }

    suspend fun checkPostVisibility(
        postId: PostId,
        viewerUserId: UserId,
    ): PostVisibilityResult {
        val post =
            transactionRunner.runInTransaction {
                postRepository.findById(postId)
            }

        if (post == null || post.status == PostStatus.DELETED) {
            return PostVisibilityResult(canView = false, post = null)
        }

        return try {
            val visiblePost = authorizePostView(post, viewerUserId)
            PostVisibilityResult(canView = true, post = visiblePost)
        } catch (_: NotFoundException) {
            PostVisibilityResult(canView = false, post = null)
        } catch (_: PermissionDeniedException) {
            PostVisibilityResult(canView = false, post = null)
        }
    }

    fun getPostLocationForGuess(postId: PostId): PostLocationForGuess {
        val post =
            transactionRunner.runInTransaction {
                postRepository.findById(postId)
            } ?: throw NotFoundException("post not found")

        if (post.status == PostStatus.DELETED) {
            throw NotFoundException("post not found")
        }

        return PostLocationForGuess(
            postId = post.id,
            authorUserId = post.authorUserId,
            location = post.location,
        )
    }

    suspend fun deletePost(
        postId: PostId,
        authorUserId: UserId,
    ) {
        userStatusChecker.requireActiveUser(authorUserId)
        val now = clock.now()

        transactionRunner.runInTransaction {
            val post =
                postRepository.findById(postId)
                    ?: throw NotFoundException("post not found")

            if (post.status == PostStatus.DELETED) {
                throw NotFoundException("post not found")
            }
            if (post.authorUserId != authorUserId) {
                throw PermissionDeniedException("only the author can delete this post")
            }

            val deleted =
                postRepository.softDelete(postId, now)
                    ?: throw NotFoundException("post not found")

            outboxRepository.append(
                OutboxEntry(
                    id = Uuid.random(),
                    eventType = PostEventTypes.DELETED,
                    aggregateType = AGGREGATE_TYPE,
                    aggregateId = deleted.id.value,
                    payloadJson =
                        encodePostEventPayload(
                            PostDeletedPayload(
                                postId = deleted.id.value.toString(),
                                authorUserId = deleted.authorUserId.value.toString(),
                                mediaId = deleted.mediaId.value.toString(),
                                deletedAt = now.toString(),
                            ),
                        ),
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun updatePostCaption(
        postId: PostId,
        authorUserId: UserId,
        caption: String,
    ): Post {
        validateCaption(caption)
        userStatusChecker.requireActiveUser(authorUserId)
        val now = clock.now()

        return transactionRunner.runInTransaction {
            val existing =
                postRepository.findById(postId)
                    ?: throw NotFoundException("post not found")

            if (existing.status == PostStatus.DELETED) {
                throw NotFoundException("post not found")
            }
            if (existing.authorUserId != authorUserId) {
                throw PermissionDeniedException("only the author can update this post")
            }

            val updated =
                postRepository.updateCaption(postId, caption, now)
                    ?: throw NotFoundException("post not found")

            outboxRepository.append(
                OutboxEntry(
                    id = Uuid.random(),
                    eventType = PostEventTypes.CAPTION_UPDATED,
                    aggregateType = AGGREGATE_TYPE,
                    aggregateId = updated.id.value,
                    payloadJson =
                        encodePostEventPayload(
                            PostCaptionUpdatedPayload(
                                postId = updated.id.value.toString(),
                                authorUserId = updated.authorUserId.value.toString(),
                                updatedAt = now.toString(),
                            ),
                        ),
                    createdAt = now,
                ),
            )
            updated
        }
    }

    suspend fun updatePostVisibility(
        postId: PostId,
        authorUserId: UserId,
        visibility: PostVisibility,
    ): Post {
        userStatusChecker.requireActiveUser(authorUserId)
        val now = clock.now()

        return transactionRunner.runInTransaction {
            val existing =
                postRepository.findById(postId)
                    ?: throw NotFoundException("post not found")

            if (existing.status == PostStatus.DELETED) {
                throw NotFoundException("post not found")
            }
            if (existing.authorUserId != authorUserId) {
                throw PermissionDeniedException("only the author can update this post")
            }

            val updated =
                postRepository.updateVisibility(postId, visibility, now)
                    ?: throw NotFoundException("post not found")

            outboxRepository.append(
                OutboxEntry(
                    id = Uuid.random(),
                    eventType = PostEventTypes.VISIBILITY_UPDATED,
                    aggregateType = AGGREGATE_TYPE,
                    aggregateId = updated.id.value,
                    payloadJson =
                        encodePostEventPayload(
                            PostVisibilityUpdatedPayload(
                                postId = updated.id.value.toString(),
                                authorUserId = updated.authorUserId.value.toString(),
                                oldVisibility = existing.visibility.name,
                                newVisibility = updated.visibility.name,
                                updatedAt = now.toString(),
                            ),
                        ),
                    createdAt = now,
                ),
            )
            updated
        }
    }

    private suspend fun filterVisiblePosts(
        posts: List<Post>,
        viewerUserId: UserId,
    ): List<Post> {
        if (posts.isEmpty()) {
            return emptyList()
        }

        val activeUsers =
            userStatusChecker.filterActiveUsers(
                posts.mapTo(mutableSetOf(viewerUserId)) { it.authorUserId },
            )
        val friendshipVisibilityByAuthor = mutableMapOf<UserId, Boolean>()

        return posts.mapNotNull { post ->
            runCatching {
                authorizePostView(
                    post = post,
                    viewerUserId = viewerUserId,
                    activeUsers = activeUsers,
                    friendshipVisibilityByAuthor = friendshipVisibilityByAuthor,
                )
            }.getOrNull()
        }
    }

    private suspend fun authorizePostView(
        post: Post,
        viewerUserId: UserId,
        activeUsers: Set<UserId>? = null,
        friendshipVisibilityByAuthor: MutableMap<UserId, Boolean>? = null,
    ): Post {
        if (post.status == PostStatus.DELETED) {
            throw NotFoundException("post not found")
        }

        if (viewerUserId == post.authorUserId) {
            if (activeUsers == null) {
                userStatusChecker.requireActiveUser(viewerUserId)
            } else if (viewerUserId !in activeUsers) {
                throw NotFoundException("post not found")
            }
            return post
        }

        if (post.visibility == PostVisibility.PRIVATE) {
            throw PermissionDeniedException("post is private")
        }

        val resolvedActiveUsers = activeUsers ?: userStatusChecker.filterActiveUsers(setOf(viewerUserId, post.authorUserId))
        if (viewerUserId !in resolvedActiveUsers || post.authorUserId !in resolvedActiveUsers) {
            throw NotFoundException("post not found")
        }

        if (post.visibility == PostVisibility.FRIENDS &&
            !canViewFriendPosts(
                viewerUserId = viewerUserId,
                authorUserId = post.authorUserId,
                friendshipVisibilityByAuthor = friendshipVisibilityByAuthor,
            )
        ) {
            throw PermissionDeniedException("post is not visible to viewer")
        }

        return post
    }

    private suspend fun canViewFriendPosts(
        viewerUserId: UserId,
        authorUserId: UserId,
        friendshipVisibilityByAuthor: MutableMap<UserId, Boolean>?,
    ): Boolean =
        friendshipVisibilityByAuthor?.getOrPut(authorUserId) {
            visibilityAuthorizer.canViewFriendPosts(viewerUserId, authorUserId)
        } ?: visibilityAuthorizer.canViewFriendPosts(viewerUserId, authorUserId)

    private fun validateCaption(caption: String) {
        if (caption.length > MAX_CAPTION_LENGTH) {
            throw ValidationException("caption must be at most $MAX_CAPTION_LENGTH characters")
        }
    }

    private fun validateLocation(location: PostLocation) {
        if (location.latitude !in -90.0..90.0) {
            throw ValidationException("latitude must be between -90 and 90")
        }
        if (location.longitude !in -180.0..180.0) {
            throw ValidationException("longitude must be between -180 and 180")
        }
        if (location.accuracyMeters <= 0.0 || location.accuracyMeters > MAX_ACCURACY_METERS) {
            throw ValidationException("accuracy_meters must be greater than 0 and at most $MAX_ACCURACY_METERS")
        }
    }
}

data class CreatePostCommand(
    val authorUserId: UserId,
    val mediaId: MediaId,
    val caption: String,
    val visibility: PostVisibility,
    val location: PostLocation,
)

data class PostVisibilityResult(
    val canView: Boolean,
    val post: Post?,
)

data class PostLocationForGuess(
    val postId: PostId,
    val authorUserId: UserId,
    val location: PostLocation,
)

class DuplicatePostMediaException(
    cause: Throwable? = null,
) : RuntimeException("media is already attached to another post", cause)

class UserNotActiveException(
    message: String = "user is not active",
) : RuntimeException(message)

class MediaNotReadyException(
    message: String = "media is not ready",
) : RuntimeException(message)

class MediaOwnershipException(
    message: String = "media is not owned by caller",
) : RuntimeException(message)
