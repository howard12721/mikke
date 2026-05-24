package jp.xhw.mikke.services.guess

import io.grpc.Status
import jp.xhw.mikke.services.guess.application.PostAuthorCannotGuessException

fun Throwable.toGuessGrpcStatus(): Status? =
    when (this) {
        is PostAuthorCannotGuessException -> Status.FAILED_PRECONDITION.withDescription(message)
        else -> null
    }
