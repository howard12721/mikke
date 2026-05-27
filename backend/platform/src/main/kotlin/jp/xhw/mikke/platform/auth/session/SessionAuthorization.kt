package jp.xhw.mikke.platform.auth.session

sealed interface ParsedSessionAuthorization {
    data class Valid(
        val sessionId: String,
    ) : ParsedSessionAuthorization

    data object Missing : ParsedSessionAuthorization

    data object Malformed : ParsedSessionAuthorization
}

object SessionAuthorization {
    const val SCHEME = "Session"

    fun parse(authorizationHeader: String?): ParsedSessionAuthorization {
        if (authorizationHeader.isNullOrBlank()) {
            return ParsedSessionAuthorization.Missing
        }

        val parts = authorizationHeader.trim().split("\\s+".toRegex(), limit = 2)
        if (parts.size != 2) {
            return ParsedSessionAuthorization.Malformed
        }

        val scheme = parts[0]
        val credential = parts[1].trim()
        if (!scheme.equals(SCHEME, ignoreCase = true)) {
            return ParsedSessionAuthorization.Malformed
        }
        if (!SessionId.isValidSessionId(credential)) {
            return ParsedSessionAuthorization.Malformed
        }

        return ParsedSessionAuthorization.Valid(credential)
    }
}
