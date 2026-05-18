package jp.xhw.mikke.platform.grpc

import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
}
