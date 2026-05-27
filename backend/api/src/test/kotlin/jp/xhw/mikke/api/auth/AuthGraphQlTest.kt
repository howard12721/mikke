package jp.xhw.mikke.api.auth

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import jp.xhw.mikke.api.apiModule
import jp.xhw.mikke.api.auth.application.*
import jp.xhw.mikke.api.auth.infrastructure.RecordingGatewaySessionReader
import jp.xhw.mikke.api.testsupport.testApiDependencies
import jp.xhw.mikke.platform.auth.session.SessionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class AuthGraphQlTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sampleSessionId =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(SessionId.BYTE_LENGTH) { 7 })
    private val sampleSessionHash = SessionId.hash(sampleSessionId)

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
                          sessionId
                          idleExpiresAt
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
            assertEquals(sampleSessionId, session.getValue("sessionId").jsonPrimitive.content)
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
                          sessionId
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
            assertEquals(sampleSessionId, session.getValue("sessionId").jsonPrimitive.content)
        }

    @Test
    fun `logout returns success when session authorization is valid`() =
        testApplicationWithAuthGateway(
            RecordingIdentityAuthGateway(
                onLogout = { command ->
                    capturedLogoutCommand = command
                },
            ),
            sessionReader = seededSessionReader(),
        ) {
            val response =
                graphQl(
                    """
                    mutation {
                      logout {
                        success
                      }
                    }
                    """.trimIndent(),
                    authorizationHeader = "Session $sampleSessionId",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(LogoutCommand(sessionHash = sampleSessionHash), capturedLogoutCommand)

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
                          sessionId
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
    fun `logout without authorization returns unauthenticated error`() =
        testApplicationWithAuthGateway(RecordingIdentityAuthGateway()) {
            val response =
                graphQl(
                    """
                    mutation {
                      logout {
                        success
                      }
                    }
                    """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)

            val error = response.graphQlFirstError()
            assertEquals("Authentication required", error.getValue("message").jsonPrimitive.content)
            val extensions = error.getValue("extensions").jsonObject
            assertEquals("UNAUTHENTICATED", extensions.getValue("code").jsonPrimitive.content)
        }

    @Test
    fun `unexpected error is masked in graphql response`() =
        testApplicationWithAuthGateway(
            RecordingIdentityAuthGateway(
                onLogout = {
                    throw IllegalStateException("database password leaked")
                },
            ),
            sessionReader = seededSessionReader(),
        ) {
            val response =
                graphQl(
                    """
                    mutation {
                      logout {
                        success
                      }
                    }
                    """.trimIndent(),
                    authorizationHeader = "Session $sampleSessionId",
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
    private var capturedLogoutCommand: LogoutCommand? = null

    private fun testApplicationWithAuthGateway(
        identityAuthGateway: IdentityAuthGateway,
        sessionReader: RecordingGatewaySessionReader = RecordingGatewaySessionReader(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        resetCaptures()

        application {
            apiModule(
                dependencies =
                    testApiDependencies(
                        authApiService = AuthApiService(identityAuthGateway = identityAuthGateway),
                        sessionReader = sessionReader,
                    ),
            )
        }

        block()
    }

    private fun seededSessionReader(): RecordingGatewaySessionReader {
        val reader = RecordingGatewaySessionReader()
        reader.putSession(
            sessionHash = sampleSessionHash,
            userId = "550e8400-e29b-41d4-a716-446655440000",
            version = 0,
            issuedAt =
                kotlin.time.Clock.System
                    .now(),
        )
        return reader
    }

    private fun resetCaptures() {
        capturedLoginCommand = null
        capturedRegisterCommand = null
        capturedLogoutCommand = null
    }

    private suspend fun ApplicationTestBuilder.graphQl(
        query: String,
        authorizationHeader: String? = null,
    ): HttpResponse =
        client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            authorizationHeader?.let { header(HttpHeaders.Authorization, it) }
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
            sessionId = sampleSessionId,
            idleExpiresAt = "2026-05-23T00:00:00Z",
            absoluteExpiresAt = "2026-10-20T00:00:00Z",
        )
}

private class RecordingIdentityAuthGateway(
    private val onLogin: suspend (LoginCommand) -> LoginResult = { error("Not implemented") },
    private val onRegister: suspend (RegisterCommand) -> RegisterResult = { error("Not implemented") },
    private val onLogout: suspend (LogoutCommand) -> Unit = { error("Not implemented") },
) : IdentityAuthGateway {
    override suspend fun login(command: LoginCommand): LoginResult = onLogin(command)

    override suspend fun register(command: RegisterCommand): RegisterResult = onRegister(command)

    override suspend fun logout(command: LogoutCommand) {
        onLogout(command)
    }
}
