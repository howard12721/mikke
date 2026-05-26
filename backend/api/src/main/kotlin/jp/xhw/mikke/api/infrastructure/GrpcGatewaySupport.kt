package jp.xhw.mikke.api.infrastructure

import com.google.protobuf.Timestamp
import io.grpc.ClientInterceptor
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.stub.MetadataUtils
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.http.ApiErrorCode
import jp.xhw.mikke.api.http.ApiHttpException
import jp.xhw.mikke.platform.auth.grpc.AuthMetadataKeys
import jp.xhw.mikke.platform.grpc.grpcClientChannelFromEnvironment
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

private val logger: Logger = Logger.getLogger("jp.xhw.mikke.api.infrastructure.GrpcGatewaySupport")

fun gatewayChannelFromEnvironment(
    targetEnv: String,
    hostEnv: String,
    portEnv: String,
    defaultPort: Int,
): ManagedChannel =
    grpcClientChannelFromEnvironment(
        targetEnv = targetEnv,
        hostEnv = hostEnv,
        portEnv = portEnv,
        defaultHost = "localhost",
        defaultPort = defaultPort,
    )

fun authHeaderInterceptor(context: ApiRequestContext): ClientInterceptor? {
    val authorizationHeader = context.authorizationHeader ?: return null
    val metadata =
        Metadata().also {
            it.put(AuthMetadataKeys.Authorization, authorizationHeader)
        }
    return MetadataUtils.newAttachHeadersInterceptor(metadata)
}

fun closeChannel(channel: ManagedChannel) {
    channel.shutdown()
    try {
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow()
        }
    } catch (e: InterruptedException) {
        channel.shutdownNow()
        Thread.currentThread().interrupt()
    }
}

fun Timestamp.toIsoString(): String = Instant.fromEpochSeconds(seconds, nanos.toLong()).toString()

/**
 * Wraps upstream gRPC calls and translates transport/domain failures to the API's error model.
 */
suspend fun <T> grpcGatewayCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e.toGatewayException()
    }

fun Exception.toGatewayException(): ApiHttpException {
    val status = Status.fromThrowable(this)
    val errorCode = status.toApiErrorCode(rootCause())
    logger.log(Level.WARNING, "Upstream gRPC call failed with status ${status.code}: ${status.description}", this)

    return ApiHttpException(
        status = errorCode.status,
        message = clientMessage(status, errorCode),
    )
}

private fun clientMessage(
    status: Status,
    errorCode: ApiErrorCode,
): String =
    when (status.code) {
        Status.Code.INVALID_ARGUMENT -> status.description ?: defaultUpstreamErrorMessage(errorCode)
        else -> defaultUpstreamErrorMessage(errorCode)
    }

private fun Status.toApiErrorCode(rootCause: Throwable?): ApiErrorCode =
    when {
        code == Status.Code.INVALID_ARGUMENT -> ApiErrorCode.InvalidRequest
        code == Status.Code.UNAUTHENTICATED -> ApiErrorCode.Unauthenticated
        code == Status.Code.PERMISSION_DENIED -> ApiErrorCode.Forbidden
        code == Status.Code.NOT_FOUND -> ApiErrorCode.NotFound
        code == Status.Code.ALREADY_EXISTS -> ApiErrorCode.Conflict
        code == Status.Code.DEADLINE_EXCEEDED -> ApiErrorCode.UpstreamTimeout
        code == Status.Code.UNAVAILABLE -> ApiErrorCode.UpstreamUnavailable
        rootCause is SocketTimeoutException || rootCause is TimeoutException -> ApiErrorCode.UpstreamTimeout
        rootCause is ConnectException || rootCause is ClosedChannelException -> ApiErrorCode.UpstreamUnavailable
        else -> ApiErrorCode.UpstreamFailure
    }

private fun Exception.rootCause(): Throwable {
    var current: Throwable = this
    while (current.cause != null) {
        current = current.cause!!
    }
    return current
}

private fun defaultUpstreamErrorMessage(errorCode: ApiErrorCode): String =
    when (errorCode) {
        ApiErrorCode.UpstreamUnavailable -> "Backend service is unavailable"
        ApiErrorCode.UpstreamTimeout -> "Backend service request timed out"
        ApiErrorCode.UpstreamFailure -> "Backend service request failed"
        ApiErrorCode.InvalidRequest -> "Invalid request"
        ApiErrorCode.Unauthenticated -> "Authentication failed"
        ApiErrorCode.Forbidden -> "Permission denied"
        ApiErrorCode.NotFound -> "Resource not found"
        ApiErrorCode.Conflict -> "Conflict"
        ApiErrorCode.InternalError -> "Internal server error"
    }
