package jp.xhw.mikke.services.media

import jp.xhw.mikke.platform.grpc.GrpcServerExceptionHandling
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait

fun main() {
    grpcServer(
        serviceName = "media-service",
        portEnv = "MEDIA_SERVICE_PORT",
        defaultPort = 50054,
        exceptionHandling = GrpcServerExceptionHandling("Internal media service error"),
    ) {
        installGrpcHealth(serviceName = "media-service")
    }.startAndAwait()
}
