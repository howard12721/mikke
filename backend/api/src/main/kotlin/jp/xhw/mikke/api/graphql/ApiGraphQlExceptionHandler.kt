package jp.xhw.mikke.api.graphql

import graphql.ErrorType
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class ApiGraphQlExceptionHandler : DataFetcherExceptionHandler {
    override fun handleException(
        handlerParameters: DataFetcherExceptionHandlerParameters,
    ): CompletableFuture<DataFetcherExceptionHandlerResult> =
        CompletableFuture.completedFuture(handlerParameters.toExceptionHandlerResult())
}

private fun DataFetcherExceptionHandlerParameters.toExceptionHandlerResult(): DataFetcherExceptionHandlerResult {
    val apiError = exception.unwrap().toApiGraphQlError()
    val error =
        GraphqlErrorBuilder
            .newError()
            .message(apiError.message)
            .location(sourceLocation)
            .path(path)
            .errorType(ErrorType.DataFetchingException)
            .extensions(apiError.extensions)
            .build()

    return DataFetcherExceptionHandlerResult
        .newResult()
        .error(error)
        .build()
}

private data class ApiGraphQlError(
    val message: String,
    val extensions: Map<String, Any>,
)

private fun Throwable.toApiGraphQlError(): ApiGraphQlError =
    when (this) {
        is ApiHttpException -> {
            ApiGraphQlError(
                message = message,
                extensions = errorExtensions(errorCode),
            )
        }

        else -> {
            ApiGraphQlError(
                message = "Internal server error",
                extensions = errorExtensions(ApiErrorCode.InternalError),
            )
        }
    }

private fun errorExtensions(errorCode: ApiErrorCode): Map<String, Any> =
    mapOf(
        "code" to errorCode.graphQlCode,
        "httpStatus" to errorCode.status.value,
    )

private val ApiErrorCode.graphQlCode: String
    get() =
        when (this) {
            ApiErrorCode.InvalidRequest -> "INVALID_REQUEST"
            ApiErrorCode.Unauthenticated -> "UNAUTHENTICATED"
            ApiErrorCode.Forbidden -> "FORBIDDEN"
            ApiErrorCode.NotFound -> "NOT_FOUND"
            ApiErrorCode.Conflict -> "CONFLICT"
            ApiErrorCode.UpstreamUnavailable -> "UPSTREAM_UNAVAILABLE"
            ApiErrorCode.UpstreamTimeout -> "UPSTREAM_TIMEOUT"
            ApiErrorCode.UpstreamFailure -> "UPSTREAM_FAILURE"
            ApiErrorCode.InternalError -> "INTERNAL_ERROR"
        }

private tailrec fun Throwable.unwrap(): Throwable =
    when (this) {
        is CompletionException -> cause?.unwrap() ?: this
        else -> this
    }
