package jp.xhw.mikke.services.identity

import io.grpc.Status
import jp.xhw.mikke.services.identity.application.exception.DuplicateIdentityUserException
import jp.xhw.mikke.services.identity.application.exception.IdentityApplicationException
import jp.xhw.mikke.services.identity.application.exception.InvalidCredentialsException
import jp.xhw.mikke.services.identity.application.exception.InvalidIdentityInputException
import jp.xhw.mikke.services.identity.application.exception.InvalidRefreshTokenException
import jp.xhw.mikke.services.identity.application.exception.UserNotFoundException

fun IdentityApplicationException.toGrpcStatus(): Status =
    when (this) {
        is InvalidIdentityInputException -> Status.INVALID_ARGUMENT.withDescription(message)
        is DuplicateIdentityUserException -> Status.ALREADY_EXISTS.withDescription(message)
        is InvalidCredentialsException -> Status.UNAUTHENTICATED.withDescription(message)
        is InvalidRefreshTokenException -> Status.UNAUTHENTICATED.withDescription(message)
        is UserNotFoundException -> Status.NOT_FOUND.withDescription(message)
    }
