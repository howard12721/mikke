package jp.xhw.mikke.services.media.application

sealed class MediaApplicationException(
    override val message: String,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidMediaInputException(
    message: String,
    cause: Throwable? = null,
) : MediaApplicationException(message, cause)

class MediaNotFoundException(
    message: String = "Media not found",
) : MediaApplicationException(message)

class MediaAccessDeniedException(
    message: String = "Media access denied",
) : MediaApplicationException(message)

class MediaDeliveryNotFoundException(
    message: String = "Media delivery not found",
) : MediaApplicationException(message)
