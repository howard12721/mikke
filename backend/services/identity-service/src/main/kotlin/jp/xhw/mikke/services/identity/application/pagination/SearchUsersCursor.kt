package jp.xhw.mikke.services.identity.application.pagination

import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.platform.pagination.PaginationCursor
import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

data class SearchUsersCursor(
    val normalizedUsername: String,
    val id: Uuid,
) : PaginationCursor {
    companion object {
        private const val VERSION = "1"
        private const val SEPARATOR = ":"

        @OptIn(ExperimentalEncodingApi::class)
        fun encode(cursor: SearchUsersCursor): String {
            val payload = "${cursor.normalizedUsername}$SEPARATOR${formatGrpcUuid(cursor.id)}"
            val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray())
            return "$VERSION.$encoded"
        }

        @OptIn(ExperimentalEncodingApi::class)
        fun decode(token: String): SearchUsersCursor {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) {
                throw ValidationException("page_token must not be blank")
            }

            val versionSeparator = trimmed.indexOf('.')
            if (versionSeparator <= 0) {
                throw ValidationException("page_token is invalid")
            }

            val version = trimmed.substring(0, versionSeparator)
            if (version != VERSION) {
                throw ValidationException("page_token version is not supported")
            }

            val encoded = trimmed.substring(versionSeparator + 1)
            val payload =
                runCatching {
                    Base64.UrlSafe.decode(encoded).decodeToString()
                }.getOrElse {
                    throw ValidationException("page_token is invalid")
                }

            val separatorIndex = payload.indexOf(SEPARATOR)
            if (separatorIndex <= 0 || separatorIndex == payload.lastIndex) {
                throw ValidationException("page_token is invalid")
            }

            val normalizedUsername = payload.substring(0, separatorIndex)
            if (normalizedUsername.isEmpty()) {
                throw ValidationException("page_token is invalid")
            }

            val id =
                parseGrpcUuid(
                    raw = payload.substring(separatorIndex + 1),
                    fieldName = "page_token.id",
                )

            return SearchUsersCursor(
                normalizedUsername = normalizedUsername,
                id = id,
            )
        }
    }
}
