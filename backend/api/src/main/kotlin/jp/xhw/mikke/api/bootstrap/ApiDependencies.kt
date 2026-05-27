package jp.xhw.mikke.api.bootstrap

import jp.xhw.mikke.api.auth.application.AuthApiService
import jp.xhw.mikke.api.auth.application.GatewaySessionAuthenticator
import jp.xhw.mikke.api.auth.application.GatewaySessionReader
import jp.xhw.mikke.api.auth.application.GatewaySessionTouchScheduler
import jp.xhw.mikke.api.auth.application.IdentityAuthGateway
import jp.xhw.mikke.api.auth.application.IdentitySessionGateway
import jp.xhw.mikke.api.auth.infrastructure.GrpcIdentityAuthGateway
import jp.xhw.mikke.api.auth.infrastructure.GrpcIdentitySessionGateway
import jp.xhw.mikke.api.auth.infrastructure.RedisGatewaySessionReader
import jp.xhw.mikke.api.common.application.GeoPoint
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.friendship.application.*
import jp.xhw.mikke.api.friendship.infrastructure.GrpcFriendshipGateway
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.guess.application.*
import jp.xhw.mikke.api.guess.infrastructure.GrpcGuessGateway
import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException
import jp.xhw.mikke.api.media.application.*
import jp.xhw.mikke.api.media.infrastructure.GrpcMediaGateway
import jp.xhw.mikke.api.post.application.Post
import jp.xhw.mikke.api.post.application.PostApiService
import jp.xhw.mikke.api.post.application.PostGateway
import jp.xhw.mikke.api.post.infrastructure.GrpcPostGateway
import jp.xhw.mikke.api.user.application.CurrentUser
import jp.xhw.mikke.api.user.application.PublicUser
import jp.xhw.mikke.api.user.application.UserApiService
import jp.xhw.mikke.api.user.application.UserGateway
import jp.xhw.mikke.api.user.infrastructure.GrpcUserGateway
import jp.xhw.mikke.platform.redis.connectRedisFromEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ApiDependencies(
    val authApiService: AuthApiService,
    val sessionAuthenticator: GatewaySessionAuthenticator,
    val touchScheduler: GatewaySessionTouchScheduler,
    val userApiService: UserApiService = UserApiService(UnavailableUserGateway),
    val mediaApiService: MediaApiService = MediaApiService(UnavailableMediaGateway),
    val postApiService: PostApiService =
        PostApiService(
            postGateway = UnavailablePostGateway,
            mediaGateway = UnavailableMediaGateway,
            userGateway = UnavailableUserGateway,
            guessGateway = UnavailableGuessGateway,
        ),
    val friendshipApiService: FriendshipApiService = FriendshipApiService(UnavailableFriendshipGateway),
    val guessApiService: GuessApiService = GuessApiService(UnavailableGuessGateway),
    private val touchScope: CoroutineScope? = null,
    private val closeables: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    override fun close() {
        touchScope?.cancel()
        closeables.forEach(AutoCloseable::close)
    }

    companion object {
        fun fromEnvironment(): ApiDependencies {
            val redis = connectRedisFromEnv()
            val sessionReader: GatewaySessionReader = RedisGatewaySessionReader(commands = redis.connection.sync())
            val sessionAuthenticator = GatewaySessionAuthenticator(sessionReader = sessionReader)
            val identityAuthGateway: IdentityAuthGateway = GrpcIdentityAuthGateway.fromEnvironment()
            val identitySessionGateway: IdentitySessionGateway = GrpcIdentitySessionGateway.fromEnvironment()
            val touchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val touchScheduler =
                GatewaySessionTouchScheduler(
                    scope = touchScope,
                    sessionReader = sessionReader,
                    identitySessionGateway = identitySessionGateway,
                )
            val userGateway = GrpcUserGateway.fromEnvironment()
            val mediaGateway = GrpcMediaGateway.fromEnvironment()
            val postGateway = GrpcPostGateway.fromEnvironment()
            val friendshipGateway = GrpcFriendshipGateway.fromEnvironment()
            val guessGateway = GrpcGuessGateway.fromEnvironment()
            val guessApiService = GuessApiService(guessGateway = guessGateway)

            return ApiDependencies(
                authApiService = AuthApiService(identityAuthGateway = identityAuthGateway),
                sessionAuthenticator = sessionAuthenticator,
                touchScheduler = touchScheduler,
                userApiService = UserApiService(userGateway = userGateway),
                mediaApiService = MediaApiService(mediaGateway = mediaGateway),
                postApiService =
                    PostApiService(
                        postGateway = postGateway,
                        mediaGateway = mediaGateway,
                        userGateway = userGateway,
                        guessGateway = guessGateway,
                    ),
                friendshipApiService = FriendshipApiService(friendshipGateway = friendshipGateway),
                guessApiService = guessApiService,
                touchScope = touchScope,
                closeables =
                    listOf(
                        identityAuthGateway,
                        identitySessionGateway,
                        userGateway,
                        mediaGateway,
                        postGateway,
                        friendshipGateway,
                        guessGateway,
                        AutoCloseable { redis.close() },
                    ),
            )
        }
    }
}

private fun unavailableFeature(): Nothing =
    throw ApiHttpException(
        status = ApiErrorCode.InternalError.status,
        message = "API feature dependency is not configured",
    )

private object UnavailableUserGateway : UserGateway {
    override suspend fun me(context: ApiRequestContext): CurrentUser = unavailableFeature()

    override suspend fun getUser(
        context: ApiRequestContext,
        userId: String,
    ): PublicUser = unavailableFeature()

    override suspend fun batchGetUsers(
        context: ApiRequestContext,
        userIds: List<String>,
    ): List<PublicUser> = unavailableFeature()

    override suspend fun searchUsers(
        context: ApiRequestContext,
        query: String,
        page: PageInput,
    ): PageResult<PublicUser> = unavailableFeature()

    override suspend fun updateProfile(
        context: ApiRequestContext,
        username: String?,
        displayName: String?,
        avatarMediaId: String?,
    ): CurrentUser = unavailableFeature()

    override suspend fun changePassword(
        context: ApiRequestContext,
        currentPassword: String,
        newPassword: String,
    ): Unit = unavailableFeature()

    override fun close() = Unit
}

private object UnavailableMediaGateway : MediaGateway {
    override suspend fun createUploadUrl(
        context: ApiRequestContext,
        contentType: String,
        contentLengthBytes: Long,
        originalFileName: String?,
    ): MediaUploadUrl = unavailableFeature()

    override suspend fun checkUpload(
        context: ApiRequestContext,
        mediaId: String,
        objectKey: String,
    ): UploadCheck = unavailableFeature()

    override suspend fun getMedia(
        context: ApiRequestContext,
        mediaId: String,
    ): Media = unavailableFeature()

    override suspend fun batchGetMedia(
        context: ApiRequestContext,
        mediaIds: List<String>,
    ): List<Media> = unavailableFeature()

    override fun close() = Unit
}

private object UnavailablePostGateway : PostGateway {
    override suspend fun createPost(
        context: ApiRequestContext,
        mediaId: String,
        caption: String?,
        visibility: String,
        location: GeoPoint,
        accuracyMeters: Double,
    ): Post = unavailableFeature()

    override suspend fun getPost(
        context: ApiRequestContext,
        postId: String,
    ): Post = unavailableFeature()

    override suspend fun listVisiblePosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post> = unavailableFeature()

    override suspend fun listMyPosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post> = unavailableFeature()

    override suspend fun updateCaption(
        context: ApiRequestContext,
        postId: String,
        caption: String,
    ): Post = unavailableFeature()

    override suspend fun updateVisibility(
        context: ApiRequestContext,
        postId: String,
        visibility: String,
    ): Post = unavailableFeature()

    override suspend fun deletePost(
        context: ApiRequestContext,
        postId: String,
    ): Unit = unavailableFeature()

    override fun close() = Unit
}

private object UnavailableFriendshipGateway : FriendshipGateway {
    override suspend fun sendRequest(
        context: ApiRequestContext,
        receiverUserId: String,
    ): FriendRequest = unavailableFeature()

    override suspend fun acceptRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): Friendship = unavailableFeature()

    override suspend fun rejectRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest = unavailableFeature()

    override suspend fun cancelRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest = unavailableFeature()

    override suspend fun removeFriend(
        context: ApiRequestContext,
        friendUserId: String,
    ): Unit = unavailableFeature()

    override suspend fun blockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ): BlockRelation = unavailableFeature()

    override suspend fun unblockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ): Unit = unavailableFeature()

    override suspend fun getFriendship(
        context: ApiRequestContext,
        targetUserId: String,
    ): FriendshipSummary = unavailableFeature()

    override suspend fun listFriends(
        context: ApiRequestContext,
        targetUserId: String,
        page: PageInput,
    ): PageResult<String> = unavailableFeature()

    override suspend fun incomingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest> = unavailableFeature()

    override suspend fun outgoingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest> = unavailableFeature()

    override fun close() = Unit
}

private object UnavailableGuessGateway : GuessGateway {
    override suspend fun submitGuess(
        context: ApiRequestContext,
        postId: String,
        guessedPoint: GeoPoint,
    ): GuessResult = unavailableFeature()

    override suspend fun getMyGuessForPost(
        context: ApiRequestContext,
        postId: String,
    ): GuessResult? = unavailableFeature()

    override suspend fun batchGetMyGuessesForPosts(
        context: ApiRequestContext,
        postIds: List<String>,
    ): List<GuessResult> = unavailableFeature()

    override suspend fun getPostGuessStats(
        context: ApiRequestContext,
        postId: String,
    ): PostGuessStats = unavailableFeature()

    override suspend fun getUserScoreSummary(
        context: ApiRequestContext,
        userId: String,
    ): UserScoreSummary = unavailableFeature()

    override suspend fun listPostRankings(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<PostUserRankingEntry> = unavailableFeature()

    override suspend fun listGuessRankings(
        context: ApiRequestContext,
        metric: String,
        page: PageInput,
    ): PageResult<GuessUserRankingEntry> = unavailableFeature()

    override fun close() = Unit
}
