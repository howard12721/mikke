package jp.xhw.mikke.platform.grpc

import io.grpc.Status
import io.grpc.StatusException
import jp.xhw.mikke.platform.auth.AuthenticatedPrincipal
import jp.xhw.mikke.platform.auth.grpc.GrpcAuthContext
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import kotlin.uuid.Uuid

fun currentAuthenticatedUser(): Uuid {
    val principal =
        GrpcAuthContext.currentPrincipal()
            ?: throw StatusException(Status.UNAUTHENTICATED.withDescription("Authentication required"))

    return principal.requireUserId()
}

fun AuthenticatedPrincipal.requireUserId(): Uuid =
    try {
        parseGrpcUuid(subject, fieldName = "subject")
    } catch (_: ValidationException) {
        throw StatusException(Status.INVALID_ARGUMENT.withDescription("Invalid authenticated user id"))
    }
