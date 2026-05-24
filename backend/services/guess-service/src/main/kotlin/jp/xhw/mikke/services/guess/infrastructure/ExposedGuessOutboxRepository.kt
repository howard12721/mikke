package jp.xhw.mikke.services.guess.infrastructure

import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.outbox.exposed.insertEntry
import jp.xhw.mikke.services.guess.application.GuessOutboxRepository

class ExposedGuessOutboxRepository : GuessOutboxRepository {
    override fun append(entry: OutboxEntry) {
        GuessOutboxTable.insertEntry(entry)
    }
}
