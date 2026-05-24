package jp.xhw.mikke.services.friendship

import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.friendship.v1.FriendshipServiceGrpc
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FriendshipServiceApplicationTest {
    @Test
    fun `health check is allowed without user authentication`() {
        val policies = friendshipGrpcAuthPolicies()

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
    fun `internal visibility check still requires internal authentication`() {
        val policies = friendshipGrpcAuthPolicies()

        assertEquals(
            GrpcEndpointAuthPolicy.InternalRequired,
            policies[FriendshipServiceGrpc.getCheckCanViewUserPostsForViewerMethod().fullMethodName],
        )
    }
}
