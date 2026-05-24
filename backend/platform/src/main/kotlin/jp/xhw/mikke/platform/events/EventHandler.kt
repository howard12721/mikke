package jp.xhw.mikke.platform.events

import jp.xhw.mikke.events.core.EventEnvelope

fun interface EventHandler<T> {
    suspend fun handle(event: EventEnvelope<T>)
}
