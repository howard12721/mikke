package jp.xhw.mikke.services.friendship.infrastructure

import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.friendship.application.port.BlockRepository
import jp.xhw.mikke.services.friendship.model.BlockRelation
import jp.xhw.mikke.services.friendship.model.UserId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class ExposedBlockRepository : BlockRepository {
    override fun save(block: BlockRelation) {
        BlocksTable.insert { row ->
            row[blockerUserId] = block.blockerUserId.value
            row[blockedUserId] = block.blockedUserId.value
            row[createdAt] = block.createdAt.toJavaInstant()
        }
    }

    override fun delete(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): Boolean {
        val deleted =
            BlocksTable.deleteWhere {
                (BlocksTable.blockerUserId eq blockerUserId.value) and
                    (BlocksTable.blockedUserId eq blockedUserId.value)
            }
        return deleted > 0
    }

    override fun find(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): BlockRelation? =
        BlocksTable
            .selectAll()
            .where {
                (BlocksTable.blockerUserId eq blockerUserId.value) and
                    (BlocksTable.blockedUserId eq blockedUserId.value)
            }.limit(1)
            .singleOrNull()
            ?.toBlockRelation()
}

private object BlocksTable : Table("blocks") {
    val blockerUserId = uuidBinary("blocker_user_id")
    val blockedUserId = uuidBinary("blocked_user_id")
    val createdAt = timestamp("created_at")
}

private fun ResultRow.toBlockRelation(): BlockRelation =
    BlockRelation(
        blockerUserId = UserId(this[BlocksTable.blockerUserId]),
        blockedUserId = UserId(this[BlocksTable.blockedUserId]),
        createdAt = this[BlocksTable.createdAt].toKotlinInstant(),
    )
