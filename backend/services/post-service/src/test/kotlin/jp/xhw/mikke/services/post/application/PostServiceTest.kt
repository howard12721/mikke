package jp.xhw.mikke.services.post.application

import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.grpc.AlreadyExistsException
import jp.xhw.mikke.platform.grpc.NotFoundException
import jp.xhw.mikke.platform.grpc.PermissionDeniedException
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.services.post.model.*
import jp.xhw.mikke.services.post.toDomain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PostServiceTest {
    private val authorId = UserId(Uuid.random())
    private val viewerId = UserId(Uuid.random())
    private val friendId = UserId(Uuid.random())
    private val mediaId = MediaId(Uuid.random())
    private val fixedInstant = Instant.fromEpochSeconds(1_700_000_000, 0)

    @Test
    fun `createPost saves active post and writes created outbox`() {
        val repository = RecordingPostRepository()
        val outbox = RecordingPostOutboxRepository()
        val service = createService(repository = repository, outbox = outbox)

        val result =
            runBlocking {
                service.createPost(
                    CreatePostCommand(
                        authorUserId = authorId,
                        mediaId = mediaId,
                        caption = "hello",
                        location = validLocation(),
                    ),
                )
            }

        assertEquals(PostStatus.ACTIVE, result.status)
        assertEquals(PostVisibility.FRIENDS, result.visibility)
        assertEquals("hello", result.caption)
        assertEquals(1, outbox.entries.size)
        assertEquals(PostEventTypes.CREATED, outbox.entries.single().eventType)
    }

    @Test
    fun `createPost rejects caption longer than 500 characters`() {
        val service = createService()

        val exception =
            assertThrows(ValidationException::class.java) {
                runBlocking {
                    service.createPost(
                        CreatePostCommand(
                            authorUserId = authorId,
                            mediaId = mediaId,
                            caption = "a".repeat(501),
                            location = validLocation(),
                        ),
                    )
                }
            }

        assertTrue(exception.message!!.contains("caption"))
    }

    @Test
    fun `createPost rejects invalid location accuracy`() {
        val service = createService()

        assertThrows(ValidationException::class.java) {
            runBlocking {
                service.createPost(
                    CreatePostCommand(
                        authorUserId = authorId,
                        mediaId = mediaId,
                        caption = "",
                        location = validLocation().copy(accuracyMeters = 0.0),
                    ),
                )
            }
        }
    }

    @Test
    fun `createPost rejects media not ready`() {
        val service =
            createService(
                mediaChecker =
                    FakePostMediaChecker(
                        result = Result.failure(MediaNotReadyException()),
                    ),
            )

        assertThrows(MediaNotReadyException::class.java) {
            runBlocking {
                service.createPost(createCommand())
            }
        }
    }

    @Test
    fun `createPost rejects media owner mismatch`() {
        val service =
            createService(
                mediaChecker =
                    FakePostMediaChecker(
                        result = Result.failure(MediaOwnershipException()),
                    ),
            )

        assertThrows(MediaOwnershipException::class.java) {
            runBlocking {
                service.createPost(createCommand())
            }
        }
    }

    @Test
    fun `getPost allows author to view own friends-only post`() {
        val post = activePost()
        val service = createService(repository = RecordingPostRepository(initial = listOf(post)))

        val result =
            runBlocking {
                service.getPost(post.id, authorId)
            }

        assertEquals(post.id, result.id)
    }

    @Test
    fun `getPost denies friends-only post to non friend`() {
        val post = activePost(visibility = PostVisibility.FRIENDS, authorUserId = friendId)
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(post)),
                visibilityAuthorizer = RecordingPostVisibilityAuthorizer(canView = false),
            )

        assertThrows(PermissionDeniedException::class.java) {
            runBlocking {
                service.getPost(post.id, viewerId)
            }
        }
    }

    @Test
    fun `getPost calls friendship authorizer for friends-only posts`() {
        val post = activePost(visibility = PostVisibility.FRIENDS, authorUserId = friendId)
        val authorizer = RecordingPostVisibilityAuthorizer(canView = true)
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(post)),
                visibilityAuthorizer = authorizer,
            )

        runBlocking {
            service.getPost(post.id, viewerId)
        }

        assertEquals(1, authorizer.calls.size)
        assertEquals(viewerId to friendId, authorizer.calls.single())
    }

    @Test
    fun `getPost does not call friendship authorizer for author viewing own post`() {
        val post = activePost(visibility = PostVisibility.FRIENDS)
        val authorizer = RecordingPostVisibilityAuthorizer(canView = true)
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(post)),
                visibilityAuthorizer = authorizer,
            )

        runBlocking {
            service.getPost(post.id, authorId)
        }

        assertTrue(authorizer.calls.isEmpty())
    }

    @Test
    fun `getPost hides deleted post`() {
        val post = activePost().copy(status = PostStatus.DELETED, deletedAt = fixedInstant)
        val service = createService(repository = RecordingPostRepository(initial = listOf(post)))

        assertThrows(NotFoundException::class.java) {
            runBlocking {
                service.getPost(post.id, authorId)
            }
        }
    }

    @Test
    fun `getPost hides post when author is inactive`() {
        val post = activePost(authorUserId = friendId, visibility = PostVisibility.FRIENDS)
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(post)),
                userStatusChecker = FakePostUserStatusChecker(activeUsers = setOf(viewerId)),
            )

        assertThrows(NotFoundException::class.java) {
            runBlocking {
                service.getPost(post.id, viewerId)
            }
        }
    }

    @Test
    fun `deletePost writes deleted outbox and hides from list my posts`() {
        val post = activePost()
        val repository = RecordingPostRepository(initial = listOf(post))
        val outbox = RecordingPostOutboxRepository()
        val service = createService(repository = repository, outbox = outbox)

        runBlocking {
            service.deletePost(post.id, authorId)
        }

        assertEquals(PostEventTypes.DELETED, outbox.entries.single().eventType)

        val listed =
            runBlocking {
                service.listMyPosts(authorId, limit = 10, cursor = null)
            }
        assertTrue(listed.items.isEmpty())
    }

    @Test
    fun `getPostLocationForGuess returns location without friendship check`() {
        val post = activePost()
        val service = createService(repository = RecordingPostRepository(initial = listOf(post)))

        val result = service.getPostLocationForGuess(post.id)

        assertEquals(post.id, result.postId)
        assertEquals(validLocation(), result.location)
    }

    @Test
    fun `listVisiblePosts returns visible friends-only posts in descending order`() {
        val older =
            activePost(
                id = PostId(Uuid.random()),
                authorUserId = friendId,
                visibility = PostVisibility.FRIENDS,
                createdAt = fixedInstant,
            )
        val newer =
            activePost(
                id = PostId(Uuid.random()),
                authorUserId = friendId,
                visibility = PostVisibility.FRIENDS,
                createdAt = Instant.fromEpochSeconds(fixedInstant.epochSeconds + 10, 0),
            )
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(older, newer)),
                visibilityAuthorizer = RecordingPostVisibilityAuthorizer(canView = true),
            )

        val slice =
            runBlocking {
                service.listVisiblePosts(viewerId, limit = 10, cursor = null)
            }

        assertEquals(listOf(newer.id, older.id), slice.items.map { it.id })
    }

    @Test
    fun `listUserPosts fills page from visible posts and advances cursor without duplicates`() {
        val visible3 =
            activePost(
                id = PostId(Uuid.random()),
                authorUserId = friendId,
                visibility = PostVisibility.FRIENDS,
                createdAt = Instant.fromEpochSeconds(fixedInstant.epochSeconds + 10, 0),
            )
        val visible2 =
            activePost(
                id = PostId(Uuid.random()),
                authorUserId = friendId,
                visibility = PostVisibility.FRIENDS,
                createdAt = Instant.fromEpochSeconds(fixedInstant.epochSeconds + 20, 0),
            )
        val visible1 =
            activePost(
                id = PostId(Uuid.random()),
                authorUserId = friendId,
                visibility = PostVisibility.FRIENDS,
                createdAt = Instant.fromEpochSeconds(fixedInstant.epochSeconds + 30, 0),
            )
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(visible3, visible2, visible1)),
                visibilityAuthorizer = RecordingPostVisibilityAuthorizer(canView = true),
            )

        val firstPage =
            runBlocking {
                service.listUserPosts(friendId, viewerId, limit = 2, cursor = null)
            }
        val secondPage =
            runBlocking {
                service.listUserPosts(
                    authorUserId = friendId,
                    viewerUserId = viewerId,
                    limit = 2,
                    cursor = CreatedAtIdCursor.decode(requireNotNull(firstPage.nextPageToken)),
                )
            }

        assertEquals(listOf(visible1.id, visible2.id), firstPage.items.map { it.id })
        assertTrue(firstPage.hasNextPage)
        assertEquals(listOf(visible3.id), secondPage.items.map { it.id })
        assertFalse(secondPage.hasNextPage)
    }

    @Test
    fun `listUserPosts hides all posts from non friend`() {
        val post = activePost(authorUserId = friendId)
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(post)),
                visibilityAuthorizer = RecordingPostVisibilityAuthorizer(canView = false),
            )

        val slice =
            runBlocking {
                service.listUserPosts(friendId, viewerId, limit = 10, cursor = null)
            }

        assertTrue(slice.items.isEmpty())
    }

    @Test
    fun `listVisiblePosts batches active user lookup and caches friendship checks per author`() {
        val firstPost =
            activePost(
                id = PostId(Uuid.random()),
                authorUserId = friendId,
                createdAt = Instant.fromEpochSeconds(fixedInstant.epochSeconds + 10, 0),
            )
        val secondPost =
            activePost(
                id = PostId(Uuid.random()),
                authorUserId = friendId,
                createdAt = Instant.fromEpochSeconds(fixedInstant.epochSeconds + 20, 0),
            )
        val userStatusChecker = FakePostUserStatusChecker()
        val visibilityAuthorizer = RecordingPostVisibilityAuthorizer(canView = true)
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(firstPost, secondPost)),
                userStatusChecker = userStatusChecker,
                visibilityAuthorizer = visibilityAuthorizer,
            )

        val slice =
            runBlocking {
                service.listVisiblePosts(viewerId, limit = 10, cursor = null)
            }

        assertEquals(listOf(secondPost.id, firstPost.id), slice.items.map { it.id })
        assertEquals(1, userStatusChecker.filterCalls.size)
        assertEquals(setOf(viewerId, friendId), userStatusChecker.filterCalls.single())
        assertEquals(listOf(viewerId to friendId), visibilityAuthorizer.calls)
    }

    @Test
    fun `listVisiblePosts propagates unexpected visibility errors`() {
        val post =
            activePost(
                id = PostId(Uuid.random()),
                authorUserId = friendId,
            )
        val service =
            createService(
                repository = RecordingPostRepository(initial = listOf(post)),
                visibilityAuthorizer = ThrowingPostVisibilityAuthorizer(IllegalStateException("friendship unavailable")),
            )

        val exception =
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    service.listVisiblePosts(viewerId, limit = 10, cursor = null)
                }
            }

        assertEquals("friendship unavailable", exception.message)
    }

    @Test
    fun `toDomain rejects unspecified visibility as validation error`() {
        assertThrows(ValidationException::class.java) {
            jp.xhw.mikke.post.v1.PostVisibility.POST_VISIBILITY_UNSPECIFIED
                .toDomain()
        }
    }

    @Test
    fun `createPost maps duplicate media to already exists`() {
        val repository =
            RecordingPostRepository(
                onSave = { throw DuplicatePostMediaException() },
            )
        val service = createService(repository = repository)

        assertThrows(AlreadyExistsException::class.java) {
            runBlocking {
                service.createPost(createCommand())
            }
        }
    }

    private fun createService(
        repository: PostRepository = RecordingPostRepository(),
        outbox: PostOutboxRepository = RecordingPostOutboxRepository(),
        mediaChecker: PostMediaChecker = FakePostMediaChecker(),
        visibilityAuthorizer: PostVisibilityAuthorizer = RecordingPostVisibilityAuthorizer(canView = true),
        userStatusChecker: PostUserStatusChecker = FakePostUserStatusChecker(),
    ): PostService =
        PostService(
            postRepository = repository,
            outboxRepository = outbox,
            mediaChecker = mediaChecker,
            visibilityAuthorizer = visibilityAuthorizer,
            userStatusChecker = userStatusChecker,
            transactionRunner = ImmediateTransactionRunner,
            clock = FixedClock(fixedInstant),
        )

    private fun createCommand(): CreatePostCommand =
        CreatePostCommand(
            authorUserId = authorId,
            mediaId = mediaId,
            caption = "hello",
            location = validLocation(),
        )

    private fun validLocation(): PostLocation =
        PostLocation(
            latitude = 35.681236,
            longitude = 139.767125,
            accuracyMeters = 10.0,
        )

    private fun activePost(
        id: PostId = PostId(Uuid.random()),
        authorUserId: UserId = this.authorId,
        visibility: PostVisibility = PostVisibility.FRIENDS,
        createdAt: Instant = fixedInstant,
    ): Post =
        Post(
            id = id,
            authorUserId = authorUserId,
            mediaId = mediaId,
            caption = "caption",
            visibility = visibility,
            status = PostStatus.ACTIVE,
            location = validLocation(),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private class FixedClock(
    private val now: Instant,
) : kotlin.time.Clock {
    override fun now(): Instant = now
}

private class RecordingPostRepository(
    initial: List<Post> = emptyList(),
    private val onSave: (Post) -> Unit = {},
) : PostRepository {
    private val posts = initial.associateBy { it.id }.toMutableMap()

    override fun save(post: Post) {
        onSave(post)
        posts[post.id] = post
    }

    override fun findById(id: PostId): Post? = posts[id]

    override fun findByIds(ids: List<PostId>): List<Post> = ids.mapNotNull { posts[it] }

    override fun softDelete(
        id: PostId,
        deletedAt: Instant,
    ): Post? {
        val existing = posts[id] ?: return null
        val deleted = existing.copy(status = PostStatus.DELETED, deletedAt = deletedAt, updatedAt = deletedAt)
        posts[id] = deleted
        return deleted
    }

    override fun updateCaption(
        id: PostId,
        caption: String,
        updatedAt: Instant,
    ): Post? {
        val existing = posts[id]?.takeIf { it.status == PostStatus.ACTIVE } ?: return null
        val updated = existing.copy(caption = caption, updatedAt = updatedAt)
        posts[id] = updated
        return updated
    }

    override fun listByAuthor(
        authorUserId: UserId,
        includeDeleted: Boolean,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Post> =
        posts.values
            .filter { it.authorUserId == authorUserId && (includeDeleted || it.status == PostStatus.ACTIVE) }
            .sortedWith(compareByDescending<Post> { it.createdAt }.thenByDescending { it.id.value.toString() })
            .let { sorted ->
                val filtered =
                    cursor?.let { c ->
                        sorted.filter {
                            it.createdAt < c.createdAt ||
                                (it.createdAt == c.createdAt && it.id.value.toString() < c.id.toString())
                        }
                    } ?: sorted
                filtered.take(limit)
            }

    override fun listActiveFriendsPosts(
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Post> =
        posts.values
            .filter { it.status == PostStatus.ACTIVE && it.visibility == PostVisibility.FRIENDS }
            .sortedWith(compareByDescending<Post> { it.createdAt }.thenByDescending { it.id.value.toString() })
            .let { sorted ->
                val filtered =
                    cursor?.let { c ->
                        sorted.filter {
                            it.createdAt < c.createdAt ||
                                (it.createdAt == c.createdAt && it.id.value.toString() < c.id.toString())
                        }
                    } ?: sorted
                filtered.take(limit)
            }
}

private class RecordingPostOutboxRepository : PostOutboxRepository {
    val entries = mutableListOf<OutboxEntry>()

    override fun append(entry: OutboxEntry) {
        entries += entry
    }
}

private class FakePostMediaChecker(
    private val result: Result<VerifiedPostMedia> =
        Result.success(
            VerifiedPostMedia(
                mediaId = MediaId(Uuid.random()),
                contentType = "image/jpeg",
            ),
        ),
) : PostMediaChecker {
    override suspend fun verifyReadyMediaOwnedBy(
        mediaId: MediaId,
        ownerUserId: UserId,
    ): VerifiedPostMedia = result.getOrThrow().copy(mediaId = mediaId)
}

private class RecordingPostVisibilityAuthorizer(
    private val canView: Boolean,
) : PostVisibilityAuthorizer {
    val calls = mutableListOf<Pair<UserId, UserId>>()

    override suspend fun canViewFriendPosts(
        viewerUserId: UserId,
        authorUserId: UserId,
    ): Boolean {
        calls += viewerUserId to authorUserId
        return canView
    }
}

private class ThrowingPostVisibilityAuthorizer(
    private val throwable: Throwable,
) : PostVisibilityAuthorizer {
    override suspend fun canViewFriendPosts(
        viewerUserId: UserId,
        authorUserId: UserId,
    ): Boolean = throw throwable
}

private class FakePostUserStatusChecker(
    private val activeUsers: Set<UserId>? = null,
) : PostUserStatusChecker {
    val filterCalls = mutableListOf<Set<UserId>>()

    override suspend fun requireActiveUser(userId: UserId) {
        if (userId !in resolvedActiveUsers(setOf(userId))) {
            throw UserNotActiveException()
        }
    }

    override suspend fun filterActiveUsers(userIds: Collection<UserId>): Set<UserId> {
        filterCalls += userIds.toSet()
        return resolvedActiveUsers(userIds)
    }

    private fun resolvedActiveUsers(userIds: Collection<UserId>): Set<UserId> = activeUsers ?: userIds.toSet()
}
