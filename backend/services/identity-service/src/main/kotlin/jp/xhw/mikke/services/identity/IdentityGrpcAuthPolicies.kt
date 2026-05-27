package jp.xhw.mikke.services.identity

import io.grpc.health.v1.HealthGrpc
import jp.xhw.mikke.identity.v1.IdentityServiceGrpc
import jp.xhw.mikke.platform.auth.grpc.GrpcEndpointAuthPolicy

fun identityGrpcAuthPolicies(): Map<String, GrpcEndpointAuthPolicy> {
    val internalRequired =
        listOf(
            IdentityServiceGrpc.getRegisterUserMethod(),
            IdentityServiceGrpc.getLoginUserMethod(),
            IdentityServiceGrpc.getTouchSessionMethod(),
            IdentityServiceGrpc.getLogoutSessionMethod(),
            IdentityServiceGrpc.getGetMeMethod(),
            IdentityServiceGrpc.getGetUserMethod(),
            IdentityServiceGrpc.getSearchUsersMethod(),
            IdentityServiceGrpc.getUpdateProfileMethod(),
            IdentityServiceGrpc.getDeactivateAccountMethod(),
            IdentityServiceGrpc.getChangePasswordMethod(),
        ).associate { method -> method.fullMethodName to GrpcEndpointAuthPolicy.internalRequired("api") }

    return internalRequired +
        mapOf(
            IdentityServiceGrpc.getBatchGetUsersMethod().fullMethodName to
                GrpcEndpointAuthPolicy.internalRequired("api", "post-service"),
            HealthGrpc.getCheckMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
            HealthGrpc.getWatchMethod().fullMethodName to GrpcEndpointAuthPolicy.UserOptional,
        )
}
