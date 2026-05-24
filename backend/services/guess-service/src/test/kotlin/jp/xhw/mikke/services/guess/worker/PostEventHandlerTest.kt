package jp.xhw.mikke.services.guess.worker

import jp.xhw.mikke.events.core.EventEnvelope
import jp.xhw.mikke.events.post.PostCreatedPayload
import jp.xhw.mikke.events.post.PostDeletedPayload
import jp.xhw.mikke.events.post.PostEventTypes
import jp.xhw.mikke.platform.database.TransactionRunner
import jp.xhw.mikke.platform.events.ProcessedEventMarkResult
import jp.xhw.mikke.platform.grpc.ValidationException
import jp.xhw.mikke.services.guess.application.PostAuthorStatsRepository
import jp.xhw.mikke.services.guess.model.PostAuthorRankingEntry
import jp.xhw.mikke.services.guess.model.UserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.uuid.Uuid

class PostEventHandlerTest {
    private val authorId = UserId(Uuid.random())

    @Test
    fun `post created increments author stats idempotently`() {
        val stats = RecordingPostAuthorStatsRepository()
        val processed = InMemoryProcessedEventGate()
        val handler =
            PostCreatedHandler(
                postAuthorStatsRepository = stats,
                transactionRunner = ImmediateTransactionRunner,
                processedEventStore = processed,
            )
        val event =
            envelope(
                eventId = Uuid.random().toString(),
                eventType = PostEventTypes.CREATED,
                payload =
                    PostCreatedPayload(
                        postId = Uuid.random().toString(),
                        authorUserId = authorId.value.toString(),
                        mediaId = Uuid.random().toString(),
                        visibility = "FRIENDS",
                        status = "ACTIVE",
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
            )

        runBlocking { handler.handle(event) }
        runBlocking { handler.handle(event) }

        assertEquals(1, stats.incremented.size)
        assertEquals(authorId, stats.incremented.single())
        assertEquals(1, processed.marked.size)
    }

    @Test
    fun `post deleted decrements author stats idempotently`() {
        val stats = RecordingPostAuthorStatsRepository()
        val processed = InMemoryProcessedEventGate()
        val handler =
            PostDeletedHandler(
                postAuthorStatsRepository = stats,
                transactionRunner = ImmediateTransactionRunner,
                processedEventStore = processed,
            )
        val event =
            envelope(
                eventId = Uuid.random().toString(),
                eventType = PostEventTypes.DELETED,
                payload =
                    PostDeletedPayload(
                        postId = Uuid.random().toString(),
                        authorUserId = authorId.value.toString(),
                        mediaId = Uuid.random().toString(),
                        deletedAt = "2026-01-01T00:00:00Z",
                    ),
            )

        runBlocking { handler.handle(event) }
        runBlocking { handler.handle(event) }

        assertEquals(1, stats.decremented.size)
        assertEquals(authorId, stats.decremented.single())
        assertEquals(1, processed.marked.size)
    }

    @Test
    fun `post created rejects invalid event id`() {
        val stats = RecordingPostAuthorStatsRepository()
        val processed = InMemoryProcessedEventGate()
        val handler =
            PostCreatedHandler(
                postAuthorStatsRepository = stats,
                transactionRunner = ImmediateTransactionRunner,
                processedEventStore = processed,
            )
        val event =
            envelope(
                eventId = "not-a-uuid",
                eventType = PostEventTypes.CREATED,
                payload =
                    PostCreatedPayload(
                        postId = Uuid.random().toString(),
                        authorUserId = authorId.value.toString(),
                        mediaId = Uuid.random().toString(),
                        visibility = "FRIENDS",
                        status = "ACTIVE",
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
            )

        assertThrows(ValidationException::class.java) {
            runBlocking { handler.handle(event) }
        }
        assertEquals(0, stats.incremented.size)
        assertEquals(0, processed.marked.size)
    }

    @Test
    fun `post deleted rejects invalid event id`() {
        val stats = RecordingPostAuthorStatsRepository()
        val processed = InMemoryProcessedEventGate()
        val handler =
            PostDeletedHandler(
                postAuthorStatsRepository = stats,
                transactionRunner = ImmediateTransactionRunner,
                processedEventStore = processed,
            )
        val event =
            envelope(
                eventId = "not-a-uuid",
                eventType = PostEventTypes.DELETED,
                payload =
                    PostDeletedPayload(
                        postId = Uuid.random().toString(),
                        authorUserId = authorId.value.toString(),
                        mediaId = Uuid.random().toString(),
                        deletedAt = "2026-01-01T00:00:00Z",
                    ),
            )

        assertThrows(ValidationException::class.java) {
            runBlocking { handler.handle(event) }
        }
        assertEquals(0, stats.decremented.size)
        assertEquals(0, processed.marked.size)
    }

    private fun <T> envelope(
        eventId: String,
        eventType: String,
        payload: T,
    ): EventEnvelope<T> =
        EventEnvelope(
            eventId = eventId,
            eventType = eventType,
            eventVersion = 1,
            occurredAt = "2026-01-01T00:00:00Z",
            producer = "post-service",
            aggregateType = "post",
            aggregateId = Uuid.random().toString(),
            payload = payload,
        )
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private class RecordingPostAuthorStatsRepository : PostAuthorStatsRepository {
    val incremented = mutableListOf<UserId>()
    val decremented = mutableListOf<UserId>()

    override fun incrementPostCount(userId: UserId) {
        incremented += userId
    }

    override fun decrementPostCount(userId: UserId) {
        decremented += userId
    }

    override fun listRankings(
        limit: Int,
        offset: Int,
    ): List<PostAuthorRankingEntry> = emptyList()
}

private class InMemoryProcessedEventGate : ProcessedEventGate {
    val marked = mutableListOf<Pair<Uuid, String>>()

    override fun tryMarkProcessed(
        eventId: Uuid,
        eventType: String,
    ): ProcessedEventMarkResult {
        if (marked.any { it.first == eventId && it.second == eventType }) {
            return ProcessedEventMarkResult.AlreadyProcessed
        }
        marked += eventId to eventType
        return ProcessedEventMarkResult.Recorded
    }
}
