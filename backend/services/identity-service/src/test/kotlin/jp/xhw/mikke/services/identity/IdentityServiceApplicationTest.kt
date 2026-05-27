package jp.xhw.mikke.services.identity

import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.identity.v1.IdentityServiceGrpc
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdentityServiceApplicationTest {
    @Test
    fun `health check is allowed without user authentication`() {
        val policies = identityGrpcAuthPolicies()

        assertEquals(
            GrpcEndpointAuthPolicy.UserOptional,
            policies[HealthGrpc.getCheckMethod().fullMethodName],
        )
        assertEquals(
            GrpcEndpointAuthPolicy.UserOptional,
            policies[HealthGrpc.getWatchMethod().fullMethodName],
        )
    }

    @Test
    fun `identity rpc methods require internal authentication`() {
        val policies = identityGrpcAuthPolicies()

        assertEquals(
            GrpcEndpointAuthPolicy.internalRequired("api"),
            policies[IdentityServiceGrpc.getGetUserMethod().fullMethodName],
        )
        assertEquals(
            GrpcEndpointAuthPolicy.internalRequired("api", "post-service"),
            policies[IdentityServiceGrpc.getBatchGetUsersMethod().fullMethodName],
        )
    }
}
