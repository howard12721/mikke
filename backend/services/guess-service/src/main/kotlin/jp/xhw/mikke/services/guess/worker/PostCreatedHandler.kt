package jp.xhw.mikke.services.guess.worker

import jp.xhw.mikke.events.core.EventEnvelope
import jp.xhw.mikke.events.post.PostCreatedPayload
import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.events.EventHandler
import jp.xhw.mikke.platform.events.ProcessedEventMarkResult
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.services.guess.application.PostAuthorStatsRepository
import jp.xhw.mikke.services.guess.model.UserId
import kotlin.uuid.Uuid

class PostCreatedHandler(
    private val postAuthorStatsRepository: PostAuthorStatsRepository,
    private val transactionRunner: TransactionRunner,
    private val processedEventStore: ProcessedEventGate,
) : EventHandler<PostCreatedPayload> {
    override suspend fun handle(event: EventEnvelope<PostCreatedPayload>) {
        val eventId = parseEventId(event.eventId)
        if (event.eventType != PostEventTypes.CREATED) {
            return
        }

        val authorUserId = UserId(parseGrpcUuid(event.payload.authorUserId, "author_user_id"))

        transactionRunner.runInTransaction {
            when (processedEventStore.tryMarkProcessed(eventId, event.eventType)) {
                ProcessedEventMarkResult.Recorded -> postAuthorStatsRepository.incrementPostCount(authorUserId)
                ProcessedEventMarkResult.AlreadyProcessed -> Unit
            }
        }
    }
}

internal fun parseEventId(raw: String): Uuid = parseGrpcUuid(raw, "event_id")
