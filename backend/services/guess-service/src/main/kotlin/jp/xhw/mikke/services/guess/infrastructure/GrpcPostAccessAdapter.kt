package jp.xhw.mikke.services.guess.infrastructure

import io.grpc.Context
import io.grpc.Metadata
import jp.xhw.mikke.platform.auth.grpc.AuthMetadataKeys
import jp.xhw.mikke.platform.grpc.InternalCallerClientInterceptor
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.post.v1.CheckPostVisibilityRequest
import jp.xhw.mikke.post.v1.GetPostLocationForGuessRequest
import jp.xhw.mikke.post.v1.PostServiceGrpcKt
import jp.xhw.mikke.services.guess.application.PostAccessPort
import jp.xhw.mikke.services.guess.application.PostLocationForGuess
import jp.xhw.mikke.services.guess.model.GeoPoint
import jp.xhw.mikke.services.guess.model.PostId
import jp.xhw.mikke.services.guess.model.UserId

object OutboundBearerTokenContext {
    private val tokenKey = Context.key<String>("mikke.guess.outbound-bearer-token")

    fun withToken(
        token: String?,
        context: Context = Context.current(),
    ): Context = context.withValue(tokenKey, token)

    fun currentToken(): String? = tokenKey.get()
}

class BearerTokenForwardingClientInterceptor : io.grpc.ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: io.grpc.MethodDescriptor<ReqT, RespT>,
        callOptions: io.grpc.CallOptions,
        next: io.grpc.Channel,
    ): io.grpc.ClientCall<ReqT, RespT> =
        object :
            io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions),
            ) {
            override fun start(
                responseListener: Listener<RespT>,
                headers: Metadata,
            ) {
                OutboundBearerTokenContext.currentToken()?.let { token ->
                    headers.put(AuthMetadataKeys.Authorization, "Bearer $token")
                }
                super.start(responseListener, headers)
            }
        }
}

class GrpcPostAccessAdapter(
    private val postStub: PostServiceGrpcKt.PostServiceCoroutineStub,
) : PostAccessPort {
    override suspend fun canViewPost(postId: PostId): Boolean {
        requireNotNull(OutboundBearerTokenContext.currentToken()) {
            "Outbound bearer token required to check post visibility; viewer is inferred from Authorization"
        }
        val response =
            postStub.checkPostVisibility(
                CheckPostVisibilityRequest
                    .newBuilder()
                    .setPostId(postId.value.toString())
                    .build(),
            )
        return response.canView
    }

    override suspend fun getPostLocationForGuess(postId: PostId): PostLocationForGuess {
        requireNotNull(OutboundBearerTokenContext.currentToken()) {
            "Outbound bearer token required to fetch post location; viewer is inferred from Authorization"
        }
        val response =
            postStub.getPostLocationForGuess(
                GetPostLocationForGuessRequest
                    .newBuilder()
                    .setPostId(postId.value.toString())
                    .build(),
            )

        return PostLocationForGuess(
            postId = PostId(parseGrpcUuid(response.postId, "post_id")),
            authorUserId = UserId(parseGrpcUuid(response.authorUserId, "author_user_id")),
            location =
                GeoPoint(
                    latitude = response.location.latitude,
                    longitude = response.location.longitude,
                ),
        )
    }
}

fun PostServiceGrpcKt.PostServiceCoroutineStub.withGuessServiceClientInterceptors(): PostServiceGrpcKt.PostServiceCoroutineStub =
    withInterceptors(
        InternalCallerClientInterceptor(serviceName = "guess-service"),
        BearerTokenForwardingClientInterceptor(),
    )
