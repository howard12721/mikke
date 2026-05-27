package jp.xhw.mikke.api.friendship

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import jp.xhw.mikke.api.apiModule
import jp.xhw.mikke.api.auth.application.*
import jp.xhw.mikke.api.bootstrap.ApiDependencies
import jp.xhw.mikke.api.common.application.PageInfo
import jp.xhw.mikke.api.common.application.PageInput
import jp.xhw.mikke.api.common.application.PageResult
import jp.xhw.mikke.api.friendship.application.*
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.media.application.Media
import jp.xhw.mikke.api.media.application.MediaGateway
import jp.xhw.mikke.api.media.application.MediaUploadUrl
import jp.xhw.mikke.api.media.application.UploadCheck
import jp.xhw.mikke.api.testsupport.testApiDependencies
import jp.xhw.mikke.api.user.application.CurrentUser
import jp.xhw.mikke.api.user.application.PublicUser
import jp.xhw.mikke.api.user.application.UserApiService
import jp.xhw.mikke.api.user.application.UserGateway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FriendshipGraphQlTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `friends returns user details through user data loader`() =
        testApplication {
            val userGateway = RecordingUserGateway()
            val friendshipGateway = RecordingFriendshipGateway(friendIds = listOf("bob-id", "carol-id"))

            application {
                apiModule(
                    dependencies =
                        testApiDependencies(
                            authApiService = AuthApiService(NoopIdentityAuthGateway),
                        ).let { base ->
                            ApiDependencies(
                                authApiService = base.authApiService,
                                sessionAuthenticator = base.sessionAuthenticator,
                                touchScheduler = base.touchScheduler,
                                userApiService = UserApiService(userGateway, NoopMediaGateway),
                                friendshipApiService = FriendshipApiService(friendshipGateway),
                                touchScope = null,
                            )
                        },
                )
            }

            val friends =
                graphQlData(
                    """
                    query {
                      friends(targetUserId: "alice-id", page: { pageSize: 20 }) {
                        users { id username displayName }
                        pageInfo { hasNextPage nextPageToken }
                      }
                    }
                    """.trimIndent(),
                    "friends",
                ).jsonObject

            assertEquals(listOf("bob-id", "carol-id"), userGateway.batchUserIds)
            assertEquals(
                listOf("bob", "carol"),
                friends
                    .getValue("users")
                    .jsonArray
                    .map {
                        it
                            .jsonObject
                            .getValue("username")
                            .jsonPrimitive
                            .content
                    },
            )
        }

    private suspend fun ApplicationTestBuilder.graphQlData(
        query: String,
        fieldName: String,
    ) = client
        .post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":${json.encodeToString(query)}}""")
        }.graphQlData(fieldName)

    private suspend fun HttpResponse.graphQlData(fieldName: String) =
        json
            .parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue(fieldName)
}

private class RecordingUserGateway : UserGateway {
    var batchUserIds: List<String> = emptyList()

    private val users =
        listOf(
            PublicUser("bob-id", "bob", "Bob", "ACTIVE", null, null),
            PublicUser("carol-id", "carol", "Carol", "ACTIVE", null, null),
        ).associateBy { it.id }

    override suspend fun me(context: ApiRequestContext): CurrentUser = error("Not implemented")

    override suspend fun getUser(
        context: ApiRequestContext,
        userId: String,
    ): PublicUser = users.getValue(userId)

    override suspend fun batchGetUsers(
        context: ApiRequestContext,
        userIds: List<String>,
    ): List<PublicUser> {
        batchUserIds = userIds
        return userIds.mapNotNull(users::get)
    }

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

private class RecordingFriendshipGateway(
    private val friendIds: List<String>,
) : FriendshipGateway {
    override suspend fun sendRequest(
        context: ApiRequestContext,
        receiverUserId: String,
    ): FriendRequest = error("Not implemented")

    override suspend fun acceptRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): Friendship = error("Not implemented")

    override suspend fun rejectRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest = error("Not implemented")

    override suspend fun cancelRequest(
        context: ApiRequestContext,
        friendRequestId: String,
    ): FriendRequest = error("Not implemented")

    override suspend fun removeFriend(
        context: ApiRequestContext,
        friendUserId: String,
    ) = error("Not implemented")

    override suspend fun blockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ): BlockRelation = error("Not implemented")

    override suspend fun unblockUser(
        context: ApiRequestContext,
        blockedUserId: String,
    ) = error("Not implemented")

    override suspend fun getFriendship(
        context: ApiRequestContext,
        targetUserId: String,
    ): FriendshipSummary = error("Not implemented")

    override suspend fun listFriends(
        context: ApiRequestContext,
        targetUserId: String,
        page: PageInput,
    ): PageResult<String> = PageResult(friendIds, PageInfo(nextPageToken = "", hasNextPage = false))

    override suspend fun incomingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest> = error("Not implemented")

    override suspend fun outgoingRequests(
        context: ApiRequestContext,
        page: PageInput,
    ): PageResult<FriendRequest> = error("Not implemented")

    override fun close() = Unit
}

private object NoopIdentityAuthGateway : IdentityAuthGateway {
    private val user =
        AuthenticatedUser(
            id = "test-user-id",
            email = "test@example.com",
            username = "test-user",
            displayName = "Test User",
            status = "ACTIVE",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )

    private val session =
        AuthSession(
            sessionId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            idleExpiresAt = "2026-01-01T01:00:00Z",
            absoluteExpiresAt = "2026-01-02T00:00:00Z",
        )

    override suspend fun login(command: LoginCommand): LoginResult = LoginResult(user, session)

    override suspend fun register(command: RegisterCommand): RegisterResult = RegisterResult(user, session)

    override suspend fun logout(command: LogoutCommand) = Unit
}

private object NoopMediaGateway : MediaGateway {
    override suspend fun createUploadUrl(
        context: ApiRequestContext,
        contentType: String,
        contentLengthBytes: Long,
        originalFileName: String?,
        generatedVariant: jp.xhw.mikke.api.media.application.GeneratedVariant,
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

    override suspend fun batchGetMedia(
        context: ApiRequestContext,
        mediaIds: List<String>,
    ): List<Media> = emptyList()

    override fun close() = Unit
}
