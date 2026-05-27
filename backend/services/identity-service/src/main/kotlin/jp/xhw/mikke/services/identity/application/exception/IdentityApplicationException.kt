package jp.xhw.mikke.services.identity.application.exception

sealed class IdentityApplicationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidIdentityInputException(
    message: String,
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

class InvalidCredentialsException(
    message: String = "Invalid credentials",
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

class UserNotFoundException(
    message: String = "User not found",
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

class DuplicateIdentityUserException(
    message: String = "identity user already exists",
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

class SessionVersionProjectionException(
    message: String = "Failed to update user session version projection",
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)

class InvalidSessionHashException(
    message: String = "Invalid session hash",
    cause: Throwable? = null,
) : IdentityApplicationException(message, cause)
