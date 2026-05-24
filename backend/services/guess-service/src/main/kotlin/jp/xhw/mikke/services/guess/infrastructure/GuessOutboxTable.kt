package jp.xhw.mikke.services.guess.infrastructure

import jp.xhw.mikke.platform.outbox.exposed.OutboxTable

object GuessOutboxTable : OutboxTable("guess_outbox")
