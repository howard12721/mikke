package jp.xhw.mikke.services.post.infrastructure

import jp.xhw.mikke.platform.outbox.exposed.OutboxTable

object PostOutboxTable : OutboxTable("post_outbox")
