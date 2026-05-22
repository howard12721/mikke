package jp.xhw.mikke.services.guess

import jp.xhw.mikke.platform.grpc.GrpcServerExceptionHandling
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait

fun main() {
    grpcServer(
        serviceName = "guess-service",
        portEnv = "GUESS_SERVICE_PORT",
        defaultPort = 50055,
        exceptionHandling = GrpcServerExceptionHandling("Internal guess service error"),
    ) {
        installGrpcHealth(serviceName = "guess-service")
    }.startAndAwait()
}
