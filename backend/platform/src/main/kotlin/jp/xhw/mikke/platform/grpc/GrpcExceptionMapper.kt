package jp.xhw.mikke.platform.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException

fun MikkeException.toStatus(): Status =
    when (this) {
        is ValidationException -> Status.INVALID_ARGUMENT.withDescription(message)
        is NotFoundException -> Status.NOT_FOUND.withDescription(message)
        is PermissionDeniedException -> Status.PERMISSION_DENIED.withDescription(message)
        is AlreadyExistsException -> Status.ALREADY_EXISTS.withDescription(message)
        is FailedPreconditionException -> Status.FAILED_PRECONDITION.withDescription(message)
        else -> Status.INTERNAL.withDescription(message)
    }

fun MikkeException.toStatusException(): StatusException = toStatus().withCause(this).asException()

fun Throwable.toStatusException(): StatusException =
    when (this) {
        is StatusException -> this
        is StatusRuntimeException -> status.withCause(this).asException(trailers)
        is MikkeException -> toStatusException()
        else -> StatusException(Status.INTERNAL.withDescription(message ?: "Internal error").withCause(this))
    }
