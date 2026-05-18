package jp.xhw.mikke.platform.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusException
import java.security.MessageDigest

object MikkeGrpcMetadata {
    const val CALLER_SERVICE = "x-mikke-caller-service"
    const val INTERNAL_TOKEN = "x-mikke-internal-token"
    const val CORRELATION_ID = "x-mikke-correlation-id"

    val callerServiceKey: Metadata.Key<String> =
        Metadata.Key.of(CALLER_SERVICE, Metadata.ASCII_STRING_MARSHALLER)
    val internalTokenKey: Metadata.Key<String> =
        Metadata.Key.of(INTERNAL_TOKEN, Metadata.ASCII_STRING_MARSHALLER)
    val correlationIdKey: Metadata.Key<String> =
        Metadata.Key.of(CORRELATION_ID, Metadata.ASCII_STRING_MARSHALLER)
}

data class InternalCallerContext(
    val callerService: String,
    val correlationId: String?,
)

object InternalRpcContext {
    private val callerKey = Context.key<InternalCallerContext>("mikke.internal.caller")

    fun withCaller(context: InternalCallerContext): Context = Context.current().withValue(callerKey, context)

    fun currentCaller(): InternalCallerContext? = callerKey.get()
}

fun requireInternalCaller(
    headers: Metadata,
    allowedServices: Set<String>,
    tokenResolver: () -> String? = ::resolveInternalRpcToken,
): InternalCallerContext {
    val expectedToken =
        tokenResolver()
            ?.takeIf { it.isNotEmpty() }
            ?: throw StatusException(Status.INTERNAL.withDescription("$INTERNAL_RPC_TOKEN_ENV is not configured"))

    val providedToken =
        headers.get(MikkeGrpcMetadata.internalTokenKey)?.trim()
            ?: throw StatusException(Status.UNAUTHENTICATED.withDescription("Internal token is required"))

    if (!constantTimeEquals(expectedToken, providedToken)) {
        throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid internal token"))
    }

    val callerService =
        headers.get(MikkeGrpcMetadata.callerServiceKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw StatusException(Status.UNAUTHENTICATED.withDescription("Caller service is required"))

    if (callerService !in allowedServices) {
        throw StatusException(Status.PERMISSION_DENIED.withDescription("Caller service is not allowed"))
    }

    return InternalCallerContext(
        callerService = callerService,
        correlationId = headers.get(MikkeGrpcMetadata.correlationIdKey)?.trim()?.takeIf { it.isNotEmpty() },
    )
}

fun requireInternalCaller(allowedServices: Set<String>): InternalCallerContext {
    val current = InternalRpcContext.currentCaller()
    if (current != null) {
        if (current.callerService !in allowedServices) {
            throw StatusException(Status.PERMISSION_DENIED.withDescription("Caller service is not allowed"))
        }
        return current
    }

    throw StatusException(Status.UNAUTHENTICATED.withDescription("Internal caller context is not available"))
}

class InternalRpcServerInterceptor : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val token = headers.get(MikkeGrpcMetadata.internalTokenKey)?.trim()
        if (token.isNullOrEmpty()) {
            return next.startCall(call, headers)
        }

        val expectedToken =
            System
                .getenv(INTERNAL_RPC_TOKEN_ENV)
                ?.takeIf { it.isNotEmpty() }
                ?: run {
                    call.close(Status.INTERNAL.withDescription("$INTERNAL_RPC_TOKEN_ENV is not configured"), Metadata())
                    return object : ServerCall.Listener<ReqT>() {}
                }

        if (!constantTimeEquals(expectedToken, token)) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid internal token"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }

        val callerService = headers.get(MikkeGrpcMetadata.callerServiceKey)?.trim()?.takeIf { it.isNotEmpty() }
        if (callerService == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("Caller service is required"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }

        val context =
            InternalRpcContext.withCaller(
                InternalCallerContext(
                    callerService = callerService,
                    correlationId = headers.get(MikkeGrpcMetadata.correlationIdKey)?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )

        return Contexts.interceptCall(context, call, headers, next)
    }
}

class InternalCallerClientInterceptor(
    private val serviceName: String,
    private val correlationId: String? = null,
) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val token =
            System
                .getenv(INTERNAL_RPC_TOKEN_ENV)
                ?.takeIf { it.isNotEmpty() }
                ?: error("$INTERNAL_RPC_TOKEN_ENV is not configured")

        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(
                responseListener: Listener<RespT>,
                headers: Metadata,
            ) {
                headers.put(MikkeGrpcMetadata.callerServiceKey, serviceName)
                headers.put(MikkeGrpcMetadata.internalTokenKey, token)
                correlationId?.let { headers.put(MikkeGrpcMetadata.correlationIdKey, it) }
                super.start(responseListener, headers)
            }
        }
    }
}

fun Metadata.withInternalCaller(
    serviceName: String,
    correlationId: String? = null,
): Metadata {
    val token =
        System
            .getenv(INTERNAL_RPC_TOKEN_ENV)
            ?.takeIf { it.isNotEmpty() }
            ?: error("$INTERNAL_RPC_TOKEN_ENV is not configured")

    put(MikkeGrpcMetadata.callerServiceKey, serviceName)
    put(MikkeGrpcMetadata.internalTokenKey, token)
    correlationId?.let { put(MikkeGrpcMetadata.correlationIdKey, it) }
    return this
}

const val INTERNAL_RPC_TOKEN_ENV = "MIKKE_INTERNAL_RPC_TOKEN"

fun resolveInternalRpcToken(): String? = System.getenv(INTERNAL_RPC_TOKEN_ENV)

private fun constantTimeEquals(
    expected: String,
    actual: String,
): Boolean {
    if (expected.length != actual.length) {
        return false
    }
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8),
    )
}
