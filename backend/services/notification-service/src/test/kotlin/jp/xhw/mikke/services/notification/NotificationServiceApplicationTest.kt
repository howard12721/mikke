package jp.xhw.mikke.services.notification

import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.notification.v1.NotificationServiceGrpc
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationServiceApplicationTest {
    @Test
    fun `push registration requires the api internal caller`() {
        val policies = notificationGrpcAuthPolicies()

        assertEquals(
            GrpcEndpointAuthPolicy.internalRequired("api"),
            policies[NotificationServiceGrpc.getRegisterPushTokenMethod().fullMethodName],
        )
        assertEquals(
            GrpcEndpointAuthPolicy.internalRequired("api"),
            policies[NotificationServiceGrpc.getDeletePushTokenMethod().fullMethodName],
        )
    }

    @Test
    fun `health checks are public`() {
        val policies = notificationGrpcAuthPolicies()

        assertEquals(
            GrpcEndpointAuthPolicy.UserOptional,
            policies[HealthGrpc.getCheckMethod().fullMethodName],
        )
    }
}
