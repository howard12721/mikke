package jp.xhw.mikke.api.graphql

import com.expediagroup.graphql.server.ktor.DefaultKtorGraphQLContextFactory
import graphql.schema.DataFetchingEnvironment
import io.ktor.http.*
import io.ktor.server.request.*
import jp.xhw.mikke.api.auth.application.GatewayAuthenticationResult
import jp.xhw.mikke.api.auth.application.GatewaySessionAuthFailure
import jp.xhw.mikke.api.auth.application.GatewaySessionAuthenticator
import jp.xhw.mikke.api.auth.application.GatewaySessionTouchScheduler
import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException
import jp.xhw.mikke.platform.auth.session.AuthenticatedActor
import jp.xhw.mikke.platform.auth.session.ParsedSessionAuthorization
import jp.xhw.mikke.platform.auth.session.SessionAuthorization

data class ApiRequestContext(
    val actor: AuthenticatedActor? = null,
    val sessionAuthFailure: GatewaySessionAuthFailure? = null,
)

class ApiGraphQlContextFactory(
    private val sessionAuthenticator: GatewaySessionAuthenticator,
    private val touchScheduler: GatewaySessionTouchScheduler,
) : DefaultKtorGraphQLContextFactory() {
    override suspend fun generateContext(request: ApplicationRequest) =
        super.generateContext(request).also { context ->
            context.put(ApiRequestContext::class, buildRequestContext(request))
        }

    private fun buildRequestContext(request: ApplicationRequest): ApiRequestContext {
        val authorizationHeader = request.header(HttpHeaders.Authorization)?.trim()?.takeIf { it.isNotEmpty() }
        return when (val parsed = SessionAuthorization.parse(authorizationHeader)) {
            ParsedSessionAuthorization.Missing -> ApiRequestContext()
            ParsedSessionAuthorization.Malformed ->
                ApiRequestContext(
                    sessionAuthFailure =
                        if (authorizationHeader?.startsWith("Session", ignoreCase = true) == true) {
                            GatewaySessionAuthFailure.MalformedHeader
                        } else {
                            null
                        },
                )
            is ParsedSessionAuthorization.Valid ->
                when (val result = sessionAuthenticator.authenticate(parsed.sessionId)) {
                    is GatewayAuthenticationResult.Authenticated -> {
                        touchScheduler.scheduleTouchIfNeeded(result.actor.sessionHash)
                        ApiRequestContext(actor = result.actor)
                    }
                    is GatewayAuthenticationResult.Failed ->
                        ApiRequestContext(sessionAuthFailure = result.reason)
                }
        }
    }
}

fun DataFetchingEnvironment.apiRequestContext(): ApiRequestContext = graphQlContext.get(ApiRequestContext::class) ?: ApiRequestContext()

fun ApiRequestContext.requireAuthenticatedActor(): AuthenticatedActor {
    if (sessionAuthFailure != null) {
        throw ApiHttpException(
            status = ApiErrorCode.Unauthenticated.status,
            message = "Authentication required",
        )
    }
    return actor
        ?: throw ApiHttpException(
            status = ApiErrorCode.Unauthenticated.status,
            message = "Authentication required",
        )
}
