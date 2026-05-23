package jp.xhw.mikke.platform.grpc

import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.util.logging.Level
import java.util.logging.Logger

fun MikkeException.toStatus(): Status =
    when (this) {
        is ValidationException -> Status.INVALID_ARGUMENT.withDescription(message)
        is NotFoundException -> Status.NOT_FOUND.withDescription(message)
        is PermissionDeniedException -> Status.PERMISSION_DENIED.withDescription(message)
        is AlreadyExistsException -> Status.ALREADY_EXISTS.withDescription(message)
        is FailedPreconditionException -> Status.FAILED_PRECONDITION.withDescription(message)
        else -> Status.INTERNAL.withDescription(message)
    }

fun interface GrpcDomainExceptionMapper {
    fun toStatus(throwable: Throwable): Status?
}

class GrpcExceptionHandlingServerInterceptor(
    private val logger: Logger,
    private val serviceName: String,
    private val internalErrorDescription: String = "Internal $serviceName error",
    private val domainExceptionMapper: GrpcDomainExceptionMapper = GrpcDomainExceptionMapper { null },
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> =
        try {
            next.startCall(call, headers).withGrpcExceptionHandling()
        } catch (e: Exception) {
            e.throwIfCancellation()
            throw e.toGrpcStatusRuntimeException(
                logger = logger,
                serviceName = serviceName,
                internalErrorDescription = internalErrorDescription,
                domainExceptionMapper = domainExceptionMapper,
            )
        }

    private fun <ReqT> ServerCall.Listener<ReqT>.withGrpcExceptionHandling(): ServerCall.Listener<ReqT> =
        object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(this) {
            override fun onMessage(message: ReqT) =
                handleGrpcListenerException {
                    super.onMessage(message)
                }

            override fun onHalfClose() =
                handleGrpcListenerException {
                    super.onHalfClose()
                }

            override fun onCancel() =
                handleGrpcListenerException {
                    super.onCancel()
                }

            override fun onComplete() =
                handleGrpcListenerException {
                    super.onComplete()
                }

            override fun onReady() =
                handleGrpcListenerException {
                    super.onReady()
                }
        }

    private inline fun handleGrpcListenerException(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            e.throwIfCancellation()
            throw e.toGrpcStatusRuntimeException(
                logger = logger,
                serviceName = serviceName,
                internalErrorDescription = internalErrorDescription,
                domainExceptionMapper = domainExceptionMapper,
            )
        }
    }
}

suspend inline fun <T> withGrpcExceptionMapping(
    logger: Logger,
    serviceName: String,
    internalErrorDescription: String = "Internal $serviceName error",
    domainExceptionMapper: GrpcDomainExceptionMapper = GrpcDomainExceptionMapper { null },
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (throwable: Exception) {
        throwable.throwIfCancellation()
        throw throwable.toGrpcStatusRuntimeException(
            logger = logger,
            serviceName = serviceName,
            internalErrorDescription = internalErrorDescription,
            domainExceptionMapper = domainExceptionMapper,
        )
    }

fun Throwable.toGrpcStatusRuntimeException(
    logger: Logger,
    serviceName: String,
    internalErrorDescription: String = "Internal $serviceName error",
    domainExceptionMapper: GrpcDomainExceptionMapper = GrpcDomainExceptionMapper { null },
): StatusRuntimeException {
    throwIfFatal()
    throwIfCancellation()

    return when (this) {
        is StatusRuntimeException -> this
        is StatusException -> status.withCause(this).asRuntimeException(trailers)
        is ValidationException ->
            Status.INVALID_ARGUMENT
                .withDescription(message)
                .withCause(this)
                .asRuntimeException()
        is MikkeException -> toStatus().withCause(this).asRuntimeException()
        else -> {
            val domainStatus = domainExceptionMapper.toStatus(this)
            if (domainStatus != null) {
                domainStatus.withCause(this).asRuntimeException()
            } else {
                logger.log(Level.SEVERE, "Unhandled $serviceName RPC exception", this)
                Status.INTERNAL
                    .withDescription(internalErrorDescription)
                    .withCause(this)
                    .asRuntimeException()
            }
        }
    }
}

@PublishedApi
internal fun Throwable.throwIfCancellation() {
    if (this is kotlinx.coroutines.CancellationException) {
        throw this
    }
}

internal fun Throwable.throwIfFatal() {
    if (this is Error) {
        throw this
    }
}
