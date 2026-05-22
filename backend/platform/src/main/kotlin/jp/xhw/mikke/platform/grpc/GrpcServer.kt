package jp.xhw.mikke.platform.grpc

import io.grpc.Server
import io.grpc.ServerBuilder
import java.util.logging.Logger

fun grpcServer(
    serviceName: String,
    portEnv: String,
    defaultPort: Int,
    exceptionHandling: GrpcServerExceptionHandling,
    configure: ServerBuilder<*>.() -> Unit = {},
): Server {
    val port = System.getenv(portEnv)?.toIntOrNull() ?: defaultPort

    return ServerBuilder
        .forPort(port)
        .apply(configure)
        .apply {
            intercept(
                GrpcExceptionHandlingServerInterceptor(
                    logger = Logger.getLogger(serviceName),
                    serviceName = serviceName,
                    internalErrorDescription = exceptionHandling.internalErrorDescription,
                    domainExceptionMapper = exceptionHandling.domainExceptionMapper,
                ),
            )
        }.build()
        .also { server ->
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    server.shutdown()
                },
            )
            println("$serviceName listening on port $port")
        }
}

fun Server.startAndAwait() {
    start()
    awaitTermination()
}

data class GrpcServerExceptionHandling(
    val internalErrorDescription: String,
    val domainExceptionMapper: GrpcDomainExceptionMapper = GrpcDomainExceptionMapper { null },
)
