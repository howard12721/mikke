package jp.xhw.mikke.api.post

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import jp.xhw.mikke.api.apiModule
import jp.xhw.mikke.api.auth.application.*
import jp.xhw.mikke.api.bootstrap.ApiDependencies
import jp.xhw.mikke.api.common.application.GeoPoint
import jp.xhw.mikke.api.common.application.PageInfo
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.guess.application.*
import jp.xhw.mikke.api.media.application.*
import jp.xhw.mikke.api.post.application.Post
import jp.xhw.mikke.api.post.application.PostApiService
import jp.xhw.mikke.api.post.application.PostGateway
import jp.xhw.mikke.api.testsupport.testApiDependencies
import jp.xhw.mikke.api.user.application.CurrentUser
import jp.xhw.mikke.api.user.application.PublicUser
import jp.xhw.mikke.api.user.application.UserGateway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PostGraphQlTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `userPosts returns requested users visible posts`() =
        testApplication {
            val postGateway = RecordingPostGateway()

            application {
                apiModule(postDependencies(postGateway))
            }

            val response =
                graphQl(
                    """
                    query {
                      userPosts(
                        userId: "author-id",
                        page: { pageSize: 2, pageToken: " next-page " }
                      ) {
                        items {
                          post { id authorUserId caption }
                          author { id username displayName }
                          media { id thumbnailUrl }
                          myGuessResult { score }
                        }
                        pageInfo { hasNextPage nextPageToken }
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("author-id", postGateway.requestedUserId)
            assertEquals(PageInput(pageSize = 2, pageToken = "next-page"), postGateway.requestedPage)

            val page = response.graphQlData("userPosts").jsonObject
            val item =
                page
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(
                "post-id",
                item
                    .getValue("post")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "author",
                item
                    .getValue("author")
                    .jsonObject
                    .getValue("username")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "https://media.example/thumbnail.jpg",
                item
                    .getValue("media")
                    .jsonObject
                    .getValue("thumbnailUrl")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "next-page-2",
                page
                    .getValue("pageInfo")
                    .jsonObject
                    .getValue("nextPageToken")
                    .jsonPrimitive
                    .content,
            )
        }

    @Test
    fun `userPosts rejects blank user id before calling post gateway`() =
        testApplication {
            val postGateway = RecordingPostGateway()

            application {
                apiModule(postDependencies(postGateway))
            }

            val response =
                graphQl(
                    """
                    query {
                      userPosts(userId: " ") {
                        items { post { id } }
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(postGateway.requestedUserId)

            val error =
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("errors")
                    .jsonArray
                    .first()
                    .jsonObject
            assertEquals("userId is required", error.getValue("message").jsonPrimitive.content)
            assertEquals(
                "INVALID_REQUEST",
                error
                    .getValue("extensions")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive
                    .content,
            )
        }

    private fun postDependencies(postGateway: PostGateway): ApiDependencies {
        val base = testApiDependencies(AuthApiService(UnusedIdentityAuthGateway))
        return ApiDependencies(
            authApiService = base.authApiService,
            sessionAuthenticator = base.sessionAuthenticator,
            touchScheduler = base.touchScheduler,
            postApiService =
                PostApiService(
                    postGateway = postGateway,
                    mediaGateway = RecordingMediaGateway,
                    userGateway = RecordingUserGateway,
                    guessGateway = RecordingGuessGateway,
                ),
            touchScope = null,
        )
    }

    private suspend fun ApplicationTestBuilder.graphQl(query: String): HttpResponse =
        client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":${json.encodeToString(query)}}""")
        }

    private suspend fun HttpResponse.graphQlData(fieldName: String) =
        json
            .parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue(fieldName)
}

private class RecordingPostGateway : PostGateway {
    var requestedUserId: String? = null
    var requestedPage: PageInput? = null

    override suspend fun listUserPosts(
        context: ApiRequestContext,
        userId: String,
        page: PageInput,
    ): PageResult<Post> {
        requestedUserId = userId
        requestedPage = page
        return PageResult(
            items =
                listOf(
                    Post(
                        id = "post-id",
                        authorUserId = "author-id",
                        mediaId = "media-id",
                        caption = "caption",
                        visibility = "FRIENDS",
                        status = "PUBLISHED",
                        createdAt = "2026-01-01T00:00:00Z",
                        updatedAt = "2026-01-01T00:00:00Z",
                    ),
                ),
            pageInfo = PageInfo(nextPageToken = "next-page-2", hasNextPage = true),
        )
    }

    override suspend fun createPost(
        context: ApiRequestContext,
        mediaId: String,
        caption: String?,
        visibility: String,
        location: GeoPoint,
        accuracyMeters: Double,
    ): Post = error("Not implemented")

    override suspend fun getPost(
        context: ApiRequestContext,
        postId: String,
    ): Post = error("Not implemented")

    override suspend fun listVisiblePosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post> = error("Not implemented")

    override suspend fun listMyPosts(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<Post> = error("Not implemented")

    override suspend fun updateCaption(
        context: ApiRequestContext,
        postId: String,
        caption: String,
    ): Post = error("Not implemented")

    override suspend fun updateVisibility(
        context: ApiRequestContext,
        postId: String,
        visibility: String,
    ): Post = error("Not implemented")

    override suspend fun deletePost(
        context: ApiRequestContext,
        postId: String,
    ) = error("Not implemented")

    override fun close() = Unit
}

private object RecordingMediaGateway : MediaGateway {
    override suspend fun batchGetMedia(
        context: ApiRequestContext,
        mediaIds: List<String>,
    ): List<Media> =
        listOf(
            Media(
                id = "media-id",
                objectKey = "media/post-id.jpg",
                originalUrl = "https://media.example/original.jpg",
                thumbnailUrl = "https://media.example/thumbnail.jpg",
                iconUrl = null,
                status = "READY",
                contentType = "image/jpeg",
                contentLengthBytes = 1024,
                etag = "etag",
                uploaderUserId = "author-id",
                createdAt = "2026-01-01T00:00:00Z",
                uploadedAt = "2026-01-01T00:00:00Z",
            ),
        )

    override suspend fun createUploadUrl(
        context: ApiRequestContext,
        contentType: String,
        contentLengthBytes: Long,
        originalFileName: String?,
        generatedVariant: GeneratedVariant,
    ): MediaUploadUrl = error("Not implemented")

    override suspend fun checkUpload(
        context: ApiRequestContext,
        mediaId: String,
        objectKey: String,
    ): UploadCheck = error("Not implemented")

    override suspend fun getMedia(
        context: ApiRequestContext,
        mediaId: String,
    ): Media = error("Not implemented")

    override fun close() = Unit
}

private object RecordingUserGateway : UserGateway {
    override suspend fun batchGetUsers(
        context: ApiRequestContext,
        userIds: List<String>,
    ): List<PublicUser> =
        listOf(
            PublicUser(
                id = "author-id",
                username = "author",
                displayName = "Author",
                status = "ACTIVE",
                avatarMediaId = null,
                avatarUrl = null,
            ),
        )

    override suspend fun me(context: ApiRequestContext): CurrentUser = error("Not implemented")

    override suspend fun getUser(
        context: ApiRequestContext,
        userId: String,
    ): PublicUser = error("Not implemented")

    override suspend fun searchUsers(
        context: ApiRequestContext,
        query: String,
        page: PageInput,
    ): PageResult<PublicUser> = error("Not implemented")

    override suspend fun updateProfile(
        context: ApiRequestContext,
        username: String?,
        displayName: String?,
        avatarMediaId: String?,
    ): CurrentUser = error("Not implemented")

    override suspend fun changePassword(
        context: ApiRequestContext,
        currentPassword: String,
        newPassword: String,
    ) = error("Not implemented")

    override fun close() = Unit
}

private object RecordingGuessGateway : GuessGateway {
    override suspend fun batchGetMyGuessesForPosts(
        context: ApiRequestContext,
        postIds: List<String>,
    ): List<GuessResult> = emptyList()

    override suspend fun submitGuess(
        context: ApiRequestContext,
        postId: String,
        guessedPoint: GeoPoint,
    ): GuessResult = error("Not implemented")

    override suspend fun getMyGuessForPost(
        context: ApiRequestContext,
        postId: String,
    ): GuessResult? = error("Not implemented")

    override suspend fun getPostGuessStats(
        context: ApiRequestContext,
        postId: String,
    ): PostGuessStats = error("Not implemented")

    override suspend fun getUserScoreSummary(
        context: ApiRequestContext,
        userId: String,
    ): UserScoreSummary = error("Not implemented")

    override suspend fun listPostRankings(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<PostUserRankingEntry> = error("Not implemented")

    override suspend fun listGuessRankings(
        context: ApiRequestContext,
        metric: String,
        page: PageInput,
    ): PageResult<GuessUserRankingEntry> = error("Not implemented")

    override fun close() = Unit
}

private object UnusedIdentityAuthGateway : IdentityAuthGateway {
    override suspend fun login(command: LoginCommand): LoginResult = error("Not implemented")

    override suspend fun register(command: RegisterCommand): RegisterResult = error("Not implemented")

    override suspend fun logout(command: LogoutCommand) = error("Not implemented")
}
