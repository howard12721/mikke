package jp.xhw.mikke.api.testsupport

import jp.xhw.mikke.api.auth.application.*
import jp.xhw.mikke.api.auth.infrastructure.RecordingGatewaySessionReader
import jp.xhw.mikke.api.bootstrap.ApiDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

fun testApiDependencies(
    authApiService: AuthApiService,
    sessionReader: GatewaySessionReader = RecordingGatewaySessionReader(),
): ApiDependencies {
    val touchScope = CoroutineScope(SupervisorJob())
    return ApiDependencies(
        authApiService = authApiService,
        sessionAuthenticator = GatewaySessionAuthenticator(sessionReader = sessionReader),
        touchScheduler =
            GatewaySessionTouchScheduler(
                scope = touchScope,
                sessionReader = sessionReader,
                identitySessionGateway = NoOpIdentitySessionGateway,
            ),
        touchScope = touchScope,
    )
}

private object NoOpIdentitySessionGateway : IdentitySessionGateway {
    override suspend fun touchSession(sessionHash: String) = Unit
}
