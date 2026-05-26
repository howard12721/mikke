package jp.xhw.mikke.api.auth

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import jp.xhw.mikke.api.apiModule
import jp.xhw.mikke.api.auth.application.*
import jp.xhw.mikke.api.bootstrap.ApiDependencies
import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthGraphQlTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `login returns session from identity client`() =
        testApplicationWithAuthGateway(
            RecordingIdentityAuthGateway(
                onLogin = { command ->
                    capturedLoginCommand = command
                    sampleLoginResult()
                },
            ),
        ) {
            val response =
                graphQl(
                    """
                    mutation {
                      login(input: { loginId: " alice@example.com ", password: "secret" }) {
                        session {
                          accessToken
                          refreshToken
                        }
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                LoginCommand(
                    loginId = "alice@example.com",
                    password = "secret",
                ),
                capturedLoginCommand,
            )

            val login = response.graphQlData("login")
            val session = login.jsonObject.getValue("session").jsonObject
            assertEquals("access-token", session.getValue("accessToken").jsonPrimitive.content)
            assertEquals("refresh-token", session.getValue("refreshToken").jsonPrimitive.content)
        }

    @Test
    fun `register returns session from identity client`() =
        testApplicationWithAuthGateway(
            RecordingIdentityAuthGateway(
                onRegister = { command ->
                    capturedRegisterCommand = command
                    sampleRegisterResult()
                },
            ),
        ) {
            val response =
                graphQl(
                    """
                    mutation {
                      register(
                        input: {
                          email: " alice@example.com "
                          username: " alice "
                          displayName: " Alice "
                          password: " password123 "
                        }
                      ) {
                        session {
                          accessToken
                        }
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                RegisterCommand(
                    email = "alice@example.com",
                    username = "alice",
                    displayName = "Alice",
                    password = "password123",
                ),
                capturedRegisterCommand,
            )

            val register = response.graphQlData("register")
            val session = register.jsonObject.getValue("session").jsonObject
            assertEquals("access-token", session.getValue("accessToken").jsonPrimitive.content)
        }

    @Test
    fun `refresh returns rotated session from identity client`() =
        testApplicationWithAuthGateway(
            RecordingIdentityAuthGateway(
                onRefresh = { command ->
                    capturedRefreshCommand = command
                    sampleRefreshResult()
                },
            ),
        ) {
            val response =
                graphQl(
                    """
                    mutation {
                      refresh(input: { refreshToken: " refresh-token " }) {
                        session {
                          accessToken
                          refreshToken
                        }
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(RefreshCommand(refreshToken = "refresh-token"), capturedRefreshCommand)

            val refresh = response.graphQlData("refresh")
            val session = refresh.jsonObject.getValue("session").jsonObject
            assertEquals("access-token", session.getValue("accessToken").jsonPrimitive.content)
            assertEquals("refresh-token", session.getValue("refreshToken").jsonPrimitive.content)
        }

    @Test
    fun `logout returns success when logout succeeds`() =
        testApplicationWithAuthGateway(
            RecordingIdentityAuthGateway(
                onLogout = { command ->
                    capturedLogoutCommand = command
                },
            ),
        ) {
            val response =
                graphQl(
                    """
                    mutation {
                      logout(input: { refreshToken: " refresh-token " }) {
                        success
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(LogoutCommand(refreshToken = "refresh-token"), capturedLogoutCommand)

            val logout = response.graphQlData("logout")
            assertEquals(
                "true",
                logout.jsonObject
                    .getValue("success")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `validation error returns graphql error extensions`() =
        testApplicationWithAuthGateway(RecordingIdentityAuthGateway()) {
            val response =
                graphQl(
                    """
                    mutation {
                      login(input: { loginId: " ", password: "secret" }) {
                        session {
                          accessToken
                        }
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)

            val error = response.graphQlFirstError()
            assertEquals("loginId is required", error.getValue("message").jsonPrimitive.content)
            val extensions = error.getValue("extensions").jsonObject
            assertEquals("INVALID_REQUEST", extensions.getValue("code").jsonPrimitive.content)
            assertEquals("400", extensions.getValue("httpStatus").jsonPrimitive.content)
        }

    @Test
    fun `upstream error returns graphql error extensions`() =
        testApplicationWithAuthGateway(
            RecordingIdentityAuthGateway(
                onRefresh = {
                    throw ApiHttpException(
                        status = ApiErrorCode.UpstreamUnavailable.status,
                        message = "Backend service is unavailable",
                    )
                },
            ),
        ) {
            val response =
                graphQl(
                    """
                    mutation {
                      refresh(input: { refreshToken: "refresh-token" }) {
                        session {
                          accessToken
                        }
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)

            val error = response.graphQlFirstError()
            assertEquals("Backend service is unavailable", error.getValue("message").jsonPrimitive.content)
            val extensions = error.getValue("extensions").jsonObject
            assertEquals("UPSTREAM_UNAVAILABLE", extensions.getValue("code").jsonPrimitive.content)
            assertEquals("503", extensions.getValue("httpStatus").jsonPrimitive.content)
        }

    @Test
    fun `unexpected error is masked in graphql response`() =
        testApplicationWithAuthGateway(
            RecordingIdentityAuthGateway(
                onLogout = {
                    throw IllegalStateException("database password leaked")
                },
            ),
        ) {
            val response =
                graphQl(
                    """
                    mutation {
                      logout(input: { refreshToken: "refresh-token" }) {
                        success
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)

            val error = response.graphQlFirstError()
            assertEquals("Internal server error", error.getValue("message").jsonPrimitive.content)
            val extensions = error.getValue("extensions").jsonObject
            assertEquals("INTERNAL_ERROR", extensions.getValue("code").jsonPrimitive.content)
            assertEquals("500", extensions.getValue("httpStatus").jsonPrimitive.content)
        }

    private var capturedLoginCommand: LoginCommand? = null
    private var capturedRegisterCommand: RegisterCommand? = null
    private var capturedRefreshCommand: RefreshCommand? = null
    private var capturedLogoutCommand: LogoutCommand? = null

    private fun testApplicationWithAuthGateway(
        identityAuthGateway: IdentityAuthGateway,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        resetCaptures()

        application {
            apiModule(
                dependencies =
                    ApiDependencies(
                        authApiService = AuthApiService(identityAuthGateway = identityAuthGateway),
                    ),
            )
        }

        block()
    }

    private fun resetCaptures() {
        capturedLoginCommand = null
        capturedRegisterCommand = null
        capturedRefreshCommand = null
        capturedLogoutCommand = null
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

    private suspend fun HttpResponse.graphQlFirstError() =
        json
            .parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("errors")
            .jsonArray
            .first()
            .jsonObject

    private fun sampleLoginResult(): LoginResult =
        LoginResult(
            user = sampleUser(),
            session = sampleSession(),
        )

    private fun sampleRegisterResult(): RegisterResult =
        RegisterResult(
            user = sampleUser(),
            session = sampleSession(),
        )

    private fun sampleRefreshResult(): RefreshResult =
        RefreshResult(
            session = sampleSession(),
        )

    private fun sampleUser(): AuthenticatedUser =
        AuthenticatedUser(
            id = "user-1",
            email = "alice@example.com",
            username = "alice",
            displayName = "Alice",
            status = "active",
            createdAt = "2026-04-21T00:00:00Z",
            updatedAt = "2026-04-21T00:00:00Z",
        )

    private fun sampleSession(): AuthSession =
        AuthSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            accessTokenExpiresAt = "2026-04-21T01:00:00Z",
            refreshTokenExpiresAt = "2026-04-28T00:00:00Z",
        )
}

private class RecordingIdentityAuthGateway(
    private val onLogin: suspend (LoginCommand) -> LoginResult = { error("Not implemented") },
    private val onRegister: suspend (RegisterCommand) -> RegisterResult = { error("Not implemented") },
    private val onRefresh: suspend (RefreshCommand) -> RefreshResult = { error("Not implemented") },
    private val onLogout: suspend (LogoutCommand) -> Unit = { error("Not implemented") },
) : IdentityAuthGateway {
    override suspend fun login(command: LoginCommand): LoginResult = onLogin(command)

    override suspend fun register(command: RegisterCommand): RegisterResult = onRegister(command)

    override suspend fun refresh(command: RefreshCommand): RefreshResult = onRefresh(command)

    override suspend fun logout(command: LogoutCommand) {
        onLogout(command)
    }
}
