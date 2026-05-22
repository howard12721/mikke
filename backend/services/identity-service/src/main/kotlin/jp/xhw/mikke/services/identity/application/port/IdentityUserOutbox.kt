package jp.xhw.mikke.services.identity.application.port

import jp.xhw.mikke.services.identity.model.IdentityUser
import jp.xhw.mikke.services.identity.model.UserId
import kotlin.time.Instant

interface IdentityUserOutbox {
    fun appendUserCreated(user: IdentityUser)

    fun appendProfileUpdated(user: IdentityUser)

    fun appendUserDeactivated(
        userId: UserId,
        deactivatedAt: Instant,
    )
}
