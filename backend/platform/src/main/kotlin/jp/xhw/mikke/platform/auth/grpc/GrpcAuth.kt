package jp.xhw.mikke.platform.auth.grpc

import io.grpc.*
import jp.xhw.mikke.platform.auth.AuthenticatedPrincipal

enum class GrpcEndpointAuthPolicy {
    UserRequired,
    UserOptional,
    InternalRequired,
}

object AuthMetadataKeys {
    val Authorization: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
}

object GrpcAuthContext {
    private val principalKey = Context.key<AuthenticatedPrincipal>("mikke.auth.principal")

    fun withPrincipal(principal: AuthenticatedPrincipal): Context = Context.current().withValue(principalKey, principal)

    fun withoutPrincipal(): Context = Context.current().withValue(principalKey, null)

    fun currentPrincipal(): AuthenticatedPrincipal? = principalKey.get()

    fun requireCurrentPrincipal(): AuthenticatedPrincipal =
        currentPrincipal() ?: error("AuthenticatedPrincipal is not available in gRPC context")
}

fun Metadata.bearerToken(): String? =
    get(AuthMetadataKeys.Authorization)
        ?.trim()
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

fun interface GrpcAuthenticator {
    fun authenticate(headers: Metadata): AuthenticatedPrincipal?
}

class GrpcAuthServerInterceptor(
    private val authenticator: GrpcAuthenticator,
    private val optional: Boolean = false,
    private val methodAuthPolicies: Map<String, GrpcEndpointAuthPolicy> = emptyMap(),
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> =
        when (authPolicyFor(call)) {
            GrpcEndpointAuthPolicy.UserRequired -> {
                interceptUserAuth(call, headers, next, required = true)
            }

            GrpcEndpointAuthPolicy.UserOptional -> {
                interceptUserAuth(call, headers, next, required = false)
            }

            GrpcEndpointAuthPolicy.InternalRequired -> {
                Contexts.interceptCall(GrpcAuthContext.withoutPrincipal(), call, headers, next)
            }
        }

    private fun <ReqT, RespT> interceptUserAuth(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
        required: Boolean,
    ): ServerCall.Listener<ReqT> {
        val principal =
            try {
                authenticator.authenticate(headers)
            } catch (_: Exception) {
                call.close(Status.UNAUTHENTICATED.withDescription("Invalid authentication"), Metadata())
                return object : ServerCall.Listener<ReqT>() {}
            }

        if (principal == null) {
            if (!required) {
                return Contexts.interceptCall(GrpcAuthContext.withoutPrincipal(), call, headers, next)
            }

            call.close(Status.UNAUTHENTICATED.withDescription("Authentication required"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }

        val context = GrpcAuthContext.withPrincipal(principal)
        return Contexts.interceptCall(context, call, headers, next)
    }

    private fun <ReqT, RespT> authPolicyFor(call: ServerCall<ReqT, RespT>): GrpcEndpointAuthPolicy {
        val methodName = call.methodDescriptor?.fullMethodName
        if (methodName != null) {
            methodAuthPolicies[methodName]?.let { return it }
        }

        return if (optional) {
            GrpcEndpointAuthPolicy.UserOptional
        } else {
            GrpcEndpointAuthPolicy.UserRequired
        }
    }
}
