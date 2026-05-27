package jp.xhw.mikke.platform.auth

import kotlin.time.Instant

data class IssuedClientSession(
    val sessionId: String,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
)
