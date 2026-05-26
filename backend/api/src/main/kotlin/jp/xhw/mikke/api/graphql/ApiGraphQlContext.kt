package jp.xhw.mikke.api.graphql

import com.expediagroup.graphql.server.ktor.DefaultKtorGraphQLContextFactory
import graphql.schema.DataFetchingEnvironment
import io.ktor.http.*
import io.ktor.server.request.*

data class ApiRequestContext(
    val authorizationHeader: String?,
)

class ApiGraphQlContextFactory : DefaultKtorGraphQLContextFactory() {
    override suspend fun generateContext(request: ApplicationRequest) =
        super.generateContext(request).also { context ->
            context.put(
                ApiRequestContext::class,
                ApiRequestContext(
                    authorizationHeader = request.header(HttpHeaders.Authorization)?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
}

fun DataFetchingEnvironment.apiRequestContext(): ApiRequestContext =
    graphQlContext.get(ApiRequestContext::class)
        ?: ApiRequestContext(authorizationHeader = null)
