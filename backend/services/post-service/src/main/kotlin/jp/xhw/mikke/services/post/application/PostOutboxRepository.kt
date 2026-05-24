package jp.xhw.mikke.services.post.application

import jp.xhw.mikke.platform.outbox.OutboxEntry

interface PostOutboxRepository {
    fun append(entry: OutboxEntry)
}
