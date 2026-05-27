package jp.xhw.mikke.platform.auth.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

object SessionLifetime {
    val idleLifetime: Duration = 30.days
    val absoluteLifetime: Duration = 180.days
    val touchThreshold: Duration = 24.hours
    val gatewayTouchDebounce: Duration = 10.minutes
}
