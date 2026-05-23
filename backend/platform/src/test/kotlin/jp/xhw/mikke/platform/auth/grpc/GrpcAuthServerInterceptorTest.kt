package jp.xhw.mikke.platform.auth.grpc

import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import jp.xhw.mikke.platform.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrpcAuthServerInterceptorTest {
    @Test
    fun `internal required policy does not run user authentication`() {
        val methodName = "mikke.test.v1.TestService/InternalOnly"
        var authenticateCalled = false
        val interceptor =
            GrpcAuthServerInterceptor(
                authenticator = {
                    authenticateCalled = true
                    null
                },
                optional = false,
                methodAuthPolicies = mapOf(methodName to GrpcEndpointAuthPolicy.InternalRequired),
            )
        val call = RecordingServerCall<String, String>(methodName)
        val handler = RecordingServerCallHandler<String, String>()

        interceptor.interceptCall(call, Metadata(), handler)

        assertFalse(authenticateCalled)
        assertTrue(handler.started)
        assertNull(call.closedStatus)
    }

    @Test
    fun `clears current principal for internal required method`() {
        val methodName = "mikke.test.v1.TestService/InternalOnly"
        val interceptor =
            GrpcAuthServerInterceptor(
                authenticator = { error("authentication should be skipped") },
                optional = false,
                methodAuthPolicies = mapOf(methodName to GrpcEndpointAuthPolicy.InternalRequired),
            )
        val call = RecordingServerCall<String, String>(methodName)
        val handler = RecordingServerCallHandler<String, String>()

        GrpcAuthContext
            .withPrincipal(AuthenticatedPrincipal(subject = "stale-user"))
            .call {
                interceptor.interceptCall(call, Metadata(), handler)
            }

        assertTrue(handler.started)
        assertNull(handler.principal)
        assertNull(call.closedStatus)
    }

    @Test
    fun `requires authentication for other methods when strict`() {
        val interceptor =
            GrpcAuthServerInterceptor(
                authenticator = { null },
                optional = false,
                methodAuthPolicies =
                    mapOf(
                        "mikke.test.v1.TestService/InternalOnly" to GrpcEndpointAuthPolicy.InternalRequired,
                    ),
            )
        val call = RecordingServerCall<String, String>("mikke.test.v1.TestService/UserOnly")
        val handler = RecordingServerCallHandler<String, String>()

        interceptor.interceptCall(call, Metadata(), handler)

        assertFalse(handler.started)
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus?.code)
        assertEquals("Authentication required", call.closedStatus?.description)
    }

    @Test
    fun `clears current principal when user authentication is optional and absent`() {
        val interceptor =
            GrpcAuthServerInterceptor(
                authenticator = { null },
                optional = true,
            )
        val call = RecordingServerCall<String, String>("mikke.test.v1.TestService/Anonymous")
        val handler = RecordingServerCallHandler<String, String>()

        GrpcAuthContext
            .withPrincipal(AuthenticatedPrincipal(subject = "stale-user"))
            .call {
                interceptor.interceptCall(call, Metadata(), handler)
            }

        assertTrue(handler.started)
        assertNull(handler.principal)
        assertNull(call.closedStatus)
    }

    @Test
    fun `adds authenticated principal to context`() {
        val principal = AuthenticatedPrincipal(subject = "user-1")
        val interceptor =
            GrpcAuthServerInterceptor(
                authenticator = { principal },
                optional = false,
            )
        val call = RecordingServerCall<String, String>("mikke.test.v1.TestService/UserOnly")
        val handler = RecordingServerCallHandler<String, String>()

        interceptor.interceptCall(call, Metadata(), handler)

        assertTrue(handler.started)
        assertEquals(principal, handler.principal)
        assertNull(call.closedStatus)
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
    var principal: AuthenticatedPrincipal? = null

    override fun startCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
    ): ServerCall.Listener<ReqT> {
        started = true
        principal = GrpcAuthContext.currentPrincipal()
        return object : ServerCall.Listener<ReqT>() {}
    }
}

private class NoopMarshaller<T> : MethodDescriptor.Marshaller<T> {
    override fun stream(value: T): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

    override fun parse(stream: java.io.InputStream): T {
        error("NoopMarshaller cannot parse values")
    }
}
