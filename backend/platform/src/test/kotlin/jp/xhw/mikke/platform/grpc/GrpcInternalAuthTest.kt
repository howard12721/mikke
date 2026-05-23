package jp.xhw.mikke.platform.grpc

import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrpcInternalAuthTest {
    @Test
    fun `accepts allowed internal caller`() {
        val headers =
            Metadata().apply {
                put(MikkeGrpcMetadata.internalTokenKey, "secret-token")
                put(MikkeGrpcMetadata.callerServiceKey, "post-service")
                put(MikkeGrpcMetadata.correlationIdKey, "corr-1")
            }

        val caller =
            requireInternalCaller(
                headers = headers,
                allowedServices = setOf("post-service", "api"),
                tokenResolver = { "secret-token" },
            )

        assertEquals("post-service", caller.callerService)
        assertEquals("corr-1", caller.correlationId)
    }

    @Test
    fun `rejects invalid internal token`() {
        val headers =
            Metadata().apply {
                put(MikkeGrpcMetadata.internalTokenKey, "wrong-token")
                put(MikkeGrpcMetadata.callerServiceKey, "post-service")
            }

        val error =
            assertThrows(StatusException::class.java) {
                requireInternalCaller(
                    headers = headers,
                    allowedServices = setOf("post-service"),
                    tokenResolver = { "secret-token" },
                )
            }

        assertEquals(Status.UNAUTHENTICATED.code, error.status.code)
    }

    @Test
    fun `rejects caller outside allowlist`() {
        val headers =
            Metadata().apply {
                put(MikkeGrpcMetadata.internalTokenKey, "secret-token")
                put(MikkeGrpcMetadata.callerServiceKey, "guess-service")
            }

        val error =
            assertThrows(StatusException::class.java) {
                requireInternalCaller(
                    headers = headers,
                    allowedServices = setOf("post-service"),
                    tokenResolver = { "secret-token" },
                )
            }

        assertEquals(Status.PERMISSION_DENIED.code, error.status.code)
    }

    @Test
    fun `internal required policy rejects missing internal token before method runs`() {
        val methodName = "mikke.test.v1.TestService/InternalOnly"
        val interceptor =
            InternalRpcServerInterceptor(
                methodAuthPolicies = mapOf(methodName to GrpcEndpointAuthPolicy.InternalRequired),
            )
        val call = RecordingServerCall<String, String>(methodName)
        val handler = RecordingServerCallHandler<String, String>()

        interceptor.interceptCall(call, Metadata(), handler)

        assertFalse(handler.started)
        assertEquals(Status.UNAUTHENTICATED.code, call.closedStatus?.code)
        assertEquals("Internal token is required", call.closedStatus?.description)
    }

    @Test
    fun `methods without internal required policy may continue without internal token`() {
        val interceptor =
            InternalRpcServerInterceptor(
                methodAuthPolicies =
                    mapOf("mikke.test.v1.TestService/InternalOnly" to GrpcEndpointAuthPolicy.InternalRequired),
            )
        val call = RecordingServerCall<String, String>("mikke.test.v1.TestService/UserOnly")
        val handler = RecordingServerCallHandler<String, String>()

        interceptor.interceptCall(call, Metadata(), handler)

        assertTrue(handler.started)
        assertEquals(null, call.closedStatus)
    }
}

private class RecordingServerCall<ReqT, RespT>(
    private val methodName: String,
) : ServerCall<ReqT, RespT>() {
    var closedStatus: Status? = null

    override fun request(numMessages: Int) = Unit

    override fun sendHeaders(headers: Metadata) = Unit

    override fun sendMessage(message: RespT) = Unit

    override fun close(
        status: Status,
        trailers: Metadata,
    ) {
        closedStatus = status
    }

    override fun isCancelled(): Boolean = false

    override fun getMethodDescriptor(): MethodDescriptor<ReqT, RespT> =
        MethodDescriptor
            .newBuilder<ReqT, RespT>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(methodName)
            .setRequestMarshaller(NoopMarshaller())
            .setResponseMarshaller(NoopMarshaller())
            .build()
}

private class RecordingServerCallHandler<ReqT, RespT> : ServerCallHandler<ReqT, RespT> {
    var started: Boolean = false

    override fun startCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
    ): ServerCall.Listener<ReqT> {
        started = true
        return object : ServerCall.Listener<ReqT>() {}
    }
}

private class NoopMarshaller<T> : MethodDescriptor.Marshaller<T> {
    override fun stream(value: T): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

    override fun parse(stream: java.io.InputStream): T {
        error("NoopMarshaller cannot parse values")
    }
}
