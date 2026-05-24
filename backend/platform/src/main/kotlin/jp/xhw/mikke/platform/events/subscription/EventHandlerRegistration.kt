package jp.xhw.mikke.platform.events.subscription

import jp.xhw.mikke.events.core.EventEnvelope
import jp.xhw.mikke.platform.events.EventHandler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class EventHandlerRegistration<T>(
    val eventType: String,
    val eventVersion: Int,
    private val payloadSerializer: KSerializer<T>,
    private val handler: EventHandler<T>,
) {
    internal suspend fun invoke(envelope: EventEnvelope<T>) {
        handler.handle(envelope)
    }

    internal suspend fun invokeParsed(envelope: EventEnvelope<*>) {
        @Suppress("UNCHECKED_CAST")
        invoke(envelope as EventEnvelope<T>)
    }

    internal fun decodePayload(
        payloadJson: String,
        json: Json,
    ): T = json.decodeFromString(payloadSerializer, payloadJson)
}
