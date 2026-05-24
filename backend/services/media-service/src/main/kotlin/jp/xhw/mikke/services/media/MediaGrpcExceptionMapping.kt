package jp.xhw.mikke.services.media

import io.grpc.Status
import jp.xhw.mikke.services.media.application.InvalidMediaInputException
import jp.xhw.mikke.services.media.application.MediaAccessDeniedException
import jp.xhw.mikke.services.media.application.MediaApplicationException
import jp.xhw.mikke.services.media.application.MediaDeliveryNotFoundException
import jp.xhw.mikke.services.media.application.MediaNotFoundException

fun MediaApplicationException.toGrpcStatus(): Status =
    when (this) {
        is InvalidMediaInputException -> Status.INVALID_ARGUMENT.withDescription(message)
        is MediaNotFoundException -> Status.NOT_FOUND.withDescription(message)
        is MediaAccessDeniedException -> Status.PERMISSION_DENIED.withDescription(message)
        is MediaDeliveryNotFoundException -> Status.NOT_FOUND.withDescription(message)
    }
