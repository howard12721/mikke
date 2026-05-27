package jp.xhw.mikke.platform.auth.session

object SessionKeys {
    fun sessionKey(sessionHash: String): String {
        require(SessionId.isValidSessionHash(sessionHash)) { "Invalid session hash format" }
        return "auth:session:$sessionHash"
    }

    fun userSessionVersionKey(userId: String): String {
        require(userId.isNotBlank()) { "User id must not be blank" }
        return "auth:user-session-version:$userId"
    }
}
