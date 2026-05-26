package jp.xhw.mikke.services.guess

import io.grpc.Status
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.services.guess.application.PostAuthorCannotGuessException

fun Throwable.toGuessGrpcStatus(): Status? =
    when (this) {
        is ValidationException -> Status.INVALID_ARGUMENT.withDescription(message)
        is PostAuthorCannotGuessException -> Status.FAILED_PRECONDITION.withDescription(message)
        else -> null
    }
