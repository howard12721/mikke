package jp.xhw.mikke.platform.auth.grpc

import io.grpc.Metadata

@ConsistentCopyVisibility
data class GrpcEndpointAuthPolicy private constructor(
    val kind: Kind,
    val allowedCallers: Set<String> = emptySet(),
) {
    enum class Kind {
        UserRequired,
        UserOptional,
        InternalRequired,
    }

    companion object {
        val UserRequired = GrpcEndpointAuthPolicy(Kind.UserRequired)
        val UserOptional = GrpcEndpointAuthPolicy(Kind.UserOptional)
        val InternalRequired = GrpcEndpointAuthPolicy(Kind.InternalRequired)

        fun internalRequired(vararg allowedCallers: String): GrpcEndpointAuthPolicy =
            GrpcEndpointAuthPolicy(
                kind = Kind.InternalRequired,
                allowedCallers = allowedCallers.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            )
    }
}

object AuthMetadataKeys {
    val Authorization: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
}
