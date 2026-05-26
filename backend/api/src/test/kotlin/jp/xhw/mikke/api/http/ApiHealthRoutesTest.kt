package jp.xhw.mikke.api.http

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import jp.xhw.mikke.api.apiModule
import jp.xhw.mikke.api.auth.application.*
import jp.xhw.mikke.api.bootstrap.ApiDependencies
import jp.xhw.mikke.platform.health.HealthResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApiHealthRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `returns service health`() =
        testApplication {
            application {
                apiModule(
                    dependencies =
                        ApiDependencies(
                            authApiService =
                                AuthApiService(
                                    identityAuthGateway =
                                        object : IdentityAuthGateway {
                                            override suspend fun login(command: LoginCommand) = error("not used")

                                            override suspend fun register(command: RegisterCommand) = error("not used")

                                            override suspend fun refresh(command: RefreshCommand): RefreshResult = error("not used")

                                            override suspend fun logout(command: LogoutCommand) = error("not used")
                                        },
                                ),
                        ),
                )
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                HealthResponse(service = "api"),
                json.decodeFromString<HealthResponse>(response.bodyAsText()),
            )
        }
}
