package jp.xhw.mikke.services.post

import io.grpc.Status
import jp.xhw.mikke.services.post.application.MediaNotReadyException
import jp.xhw.mikke.services.post.application.MediaOwnershipException
import jp.xhw.mikke.services.post.application.UserNotActiveException

fun Throwable.toPostGrpcStatus(): Status? =
    when (this) {
        is UserNotActiveException -> Status.NOT_FOUND.withDescription(message)
        is MediaNotReadyException -> Status.FAILED_PRECONDITION.withDescription(message)
        is MediaOwnershipException -> Status.PERMISSION_DENIED.withDescription(message)
        else -> null
    }
