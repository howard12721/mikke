package jp.xhw.mikke.api.http

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable

fun Application.configureApiErrorHandling() {
    install(StatusPages) {
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                status = ApiErrorCode.InvalidRequest.status,
                message = ApiErrorResponse(message = "Invalid request body"),
            )
        }
        exception<ApiHttpException> { call, cause ->
            call.respond(
                status = cause.status,
                message = ApiErrorResponse(message = cause.message),
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                status = ApiErrorCode.InternalError.status,
                message = ApiErrorResponse(message = cause.message ?: "Internal server error"),
            )
        }
    }
}

@Serializable
data class ApiErrorResponse(
    val message: String,
)

class ApiHttpException(
    val status: HttpStatusCode,
    override val message: String,
) : RuntimeException(message) {
    val errorCode: ApiErrorCode = ApiErrorCode.fromStatus(status)
}

enum class ApiErrorCode(
    val status: HttpStatusCode,
) {
    InvalidRequest(HttpStatusCode.BadRequest),
    Unauthenticated(HttpStatusCode.Unauthorized),
    Forbidden(HttpStatusCode.Forbidden),
    NotFound(HttpStatusCode.NotFound),
    Conflict(HttpStatusCode.Conflict),
    UpstreamUnavailable(HttpStatusCode.ServiceUnavailable),
    UpstreamTimeout(HttpStatusCode.GatewayTimeout),
    UpstreamFailure(HttpStatusCode.BadGateway),
    InternalError(HttpStatusCode.InternalServerError),
    ;

    companion object {
        fun fromStatus(status: HttpStatusCode): ApiErrorCode = entries.firstOrNull { it.status == status } ?: InternalError
    }
}
