package jp.xhw.mikke.platform.grpc

open class MikkeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ValidationException(
    message: String,
    cause: Throwable? = null,
) : MikkeException(message, cause)

class NotFoundException(
    message: String,
    cause: Throwable? = null,
) : MikkeException(message, cause)

class PermissionDeniedException(
    message: String,
    cause: Throwable? = null,
) : MikkeException(message, cause)

class AlreadyExistsException(
    message: String,
    cause: Throwable? = null,
) : MikkeException(message, cause)

class FailedPreconditionException(
    message: String,
    cause: Throwable? = null,
) : MikkeException(message, cause)
