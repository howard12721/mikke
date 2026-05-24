package jp.xhw.mikke.services.guess.infrastructure

import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import jp.xhw.mikke.platform.auth.grpc.bearerToken

class OutboundBearerTokenCaptureInterceptor : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val context = OutboundBearerTokenContext.withToken(headers.bearerToken())
        return Contexts.interceptCall(context, call, headers, next)
    }
}
