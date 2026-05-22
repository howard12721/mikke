package jp.xhw.mikke.services.post.infrastructure

import jp.xhw.mikke.platform.outbox.OutboxEntry
import jp.xhw.mikke.platform.outbox.exposed.OutboxTable
import jp.xhw.mikke.platform.outbox.exposed.insertEntry
import jp.xhw.mikke.services.post.application.PostOutboxRepository

class ExposedPostOutboxRepository : PostOutboxRepository {
    override fun append(entry: OutboxEntry) {
        PostOutboxTable.insertEntry(entry)
    }
}

private object PostOutboxTable : OutboxTable("post_outbox")
