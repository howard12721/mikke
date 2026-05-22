package jp.xhw.mikke.services.notification

import jp.xhw.mikke.platform.grpc.GrpcServerExceptionHandling
import jp.xhw.mikke.platform.grpc.grpcServer
import jp.xhw.mikke.platform.grpc.installGrpcHealth
import jp.xhw.mikke.platform.grpc.startAndAwait

fun main() {
    grpcServer(
        serviceName = "notification-service",
        portEnv = "NOTIFICATION_SERVICE_PORT",
        defaultPort = 50057,
        exceptionHandling = GrpcServerExceptionHandling("Internal notification service error"),
    ) {
        installGrpcHealth(serviceName = "notification-service")
    }.startAndAwait()
}
