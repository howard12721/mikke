package jp.xhw.mikke.platform.grpc

import io.grpc.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException as CoroutineCancellationException
import java.util.concurrent.CancellationException as FutureCancellationException

class GrpcExceptionMapperTest {
    @Test
    fun `maps domain exceptions to grpc status`() {
        assertEquals(Status.Code.INVALID_ARGUMENT, ValidationException("bad").toStatus().code)
        assertEquals(Status.Code.NOT_FOUND, NotFoundException("missing").toStatus().code)
        assertEquals(Status.Code.PERMISSION_DENIED, PermissionDeniedException("denied").toStatus().code)
        assertEquals(Status.Code.ALREADY_EXISTS, AlreadyExistsException("dup").toStatus().code)
        assertEquals(Status.Code.FAILED_PRECONDITION, FailedPreconditionException("conflict").toStatus().code)
    }

    @Test
    fun `exception handling interceptor maps listener exception`() {
        val listener = listenerThrowing(TestDomainException("missing"))

        val thrown =
            org.junit.jupiter.api.assertThrows<Throwable> {
                listener.onHalfClose()
            }

        val status = Status.fromThrowable(thrown)
        assertEquals(Status.Code.NOT_FOUND, status.code)
        assertEquals("missing", status.description)
    }

    @Test
    fun `exception handling interceptor maps validation exception`() {
        val listener = listenerThrowing(ValidationException("bad input"))

        val thrown =
            org.junit.jupiter.api.assertThrows<Throwable> {
                listener.onHalfClose()
            }

        val status = Status.fromThrowable(thrown)
        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertEquals("bad input", status.description)
    }

    @Test
    fun `exception handling interceptor masks unexpected exception`() {
        val listener = listenerThrowing(IllegalStateException("sensitive detail"))

        val thrown =
            org.junit.jupiter.api.assertThrows<Throwable> {
                listener.onHalfClose()
            }

        val status = Status.fromThrowable(thrown)
        assertEquals(Status.Code.INTERNAL, status.code)
        assertEquals("Internal test-service error", status.description)
    }

    @Test
    fun `exception handling interceptor rethrows fatal error`() {
        val fatal = StackOverflowError("fatal")
        val listener = listenerThrowing(fatal)

        val thrown =
            org.junit.jupiter.api.assertThrows<StackOverflowError> {
                listener.onHalfClose()
            }

        assertSame(fatal, thrown)
    }

    @Test
    fun `coroutine exception mapping maps domain exception`() =
        runBlocking {
            val thrown =
                org.junit.jupiter.api.assertThrows<Throwable> {
                    withGrpcExceptionMapping(
                        logger = testLogger,
                        serviceName = "test-service",
                        domainExceptionMapper =
                            GrpcDomainExceptionMapper { candidate ->
                                (candidate as? TestDomainException)?.let {
                                    Status.NOT_FOUND.withDescription(it.message)
                                }
                            },
                    ) {
                        throw TestDomainException("missing")
                    }
                }

            val status = Status.fromThrowable(thrown)
            assertEquals(Status.Code.NOT_FOUND, status.code)
            assertEquals("missing", status.description)
        }

    @Test
    fun `coroutine exception mapping rethrows cancellation`() {
        val cancellation = CoroutineCancellationException("cancelled")

        val thrown =
            org.junit.jupiter.api.assertThrows<CoroutineCancellationException> {
                runBlocking {
                    withGrpcExceptionMapping(
                        logger = testLogger,
                        serviceName = "test-service",
                    ) {
                        throw cancellation
                    }
                }
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `coroutine exception mapping rethrows future cancellation`() {
        val cancellation = FutureCancellationException("cancelled")

        val thrown =
            org.junit.jupiter.api.assertThrows<FutureCancellationException> {
                runBlocking {
                    withGrpcExceptionMapping(
                        logger = testLogger,
                        serviceName = "test-service",
                    ) {
                        throw cancellation
                    }
                }
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `coroutine exception mapping rethrows fatal error`() {
        val fatal = StackOverflowError("fatal")

        val thrown =
            org.junit.jupiter.api.assertThrows<StackOverflowError> {
                runBlocking {
                    withGrpcExceptionMapping(
                        logger = testLogger,
                        serviceName = "test-service",
                    ) {
                        throw fatal
                    }
                }
            }

        assertSame(fatal, thrown)
    }

    @Test
    fun `grpc status mapper rethrows cancellation`() {
        val cancellation = CoroutineCancellationException("cancelled")

        val thrown =
            org.junit.jupiter.api.assertThrows<CoroutineCancellationException> {
                cancellation.toGrpcStatusRuntimeException(
                    logger = testLogger,
                    serviceName = "test-service",
                )
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `grpc status mapper rethrows future cancellation`() {
        val cancellation = FutureCancellationException("cancelled")

        val thrown =
            org.junit.jupiter.api.assertThrows<FutureCancellationException> {
                cancellation.toGrpcStatusRuntimeException(
                    logger = testLogger,
                    serviceName = "test-service",
                )
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `grpc status mapper rethrows fatal error`() {
        val fatal = StackOverflowError("fatal")

        val thrown =
            org.junit.jupiter.api.assertThrows<StackOverflowError> {
                fatal.toGrpcStatusRuntimeException(
                    logger = testLogger,
                    serviceName = "test-service",
                )
            }

        assertSame(fatal, thrown)
    }

    private fun listenerThrowing(throwable: Throwable): ServerCall.Listener<String> {
        val interceptor =
            GrpcExceptionHandlingServerInterceptor(
                logger = testLogger,
                serviceName = "test-service",
                domainExceptionMapper =
                    GrpcDomainExceptionMapper { candidate ->
                        (candidate as? TestDomainException)?.let {
                            Status.NOT_FOUND.withDescription(it.message)
                        }
                    },
            )

        return interceptor.interceptCall(
            NoopServerCall<String, String>(),
            Metadata(),
            ServerCallHandler<String, String> { _, _ ->
                object : ServerCall.Listener<String>() {
                    override fun onHalfClose(): Unit = throw throwable
                }
            },
        )
    }

    private class TestDomainException(
        message: String,
    ) : RuntimeException(message)

    private companion object {
        val testLogger: Logger = Logger.getLogger(GrpcExceptionMapperTest::class.java.name)
    }
}

private class NoopServerCall<ReqT, RespT> : ServerCall<ReqT, RespT>() {
    override fun request(numMessages: Int) = Unit

    override fun sendHeaders(headers: Metadata) = Unit

    override fun sendMessage(message: RespT) = Unit

    override fun close(
        status: Status,
        trailers: Metadata,
    ) = Unit

    override fun isCancelled(): Boolean = false

    override fun getMethodDescriptor(): MethodDescriptor<ReqT, RespT>? = null
}
