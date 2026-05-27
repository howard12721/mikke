package jp.xhw.mikke.services.identity

import io.grpc.Status
import jp.xhw.mikke.services.identity.application.exception.*

fun IdentityApplicationException.toGrpcStatus(): Status =
    when (this) {
        is InvalidIdentityInputException -> Status.INVALID_ARGUMENT.withDescription(message)
        is DuplicateIdentityUserException -> Status.ALREADY_EXISTS.withDescription(message)
        is InvalidCredentialsException -> Status.UNAUTHENTICATED.withDescription(message)
        is InvalidSessionHashException -> Status.INVALID_ARGUMENT.withDescription(message)
        is SessionVersionProjectionException -> Status.INTERNAL.withDescription(message)
        is UserNotFoundException -> Status.NOT_FOUND.withDescription(message)
    }
