package jp.xhw.mikke.api.infrastructure

import io.grpc.ClientInterceptor
import io.grpc.stub.AbstractStub
import jp.xhw.mikke.api.graphql.ApiRequestContext
import jp.xhw.mikke.api.graphql.requireAuthenticatedActor
import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.platform.auth.session.AuthenticatedActor
import jp.xhw.mikke.platform.grpc.InternalCallerClientInterceptor

private const val GATEWAY_CALLER_SERVICE = "api"

fun internalAuthInterceptor(): ClientInterceptor = InternalCallerClientInterceptor(serviceName = GATEWAY_CALLER_SERVICE)

fun <T : AbstractStub<T>> T.withInternalAuth(): T = withInterceptors(internalAuthInterceptor())

fun AuthenticatedActor.toProto(): ActorContext =
    ActorContext
        .newBuilder()
        .setUserId(userId)
        .setSessionHash(sessionHash)
        .build()

fun ApiRequestContext.requireActorProto(): ActorContext = requireAuthenticatedActor().toProto()
