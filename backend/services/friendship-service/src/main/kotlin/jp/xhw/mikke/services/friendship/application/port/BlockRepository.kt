package jp.xhw.mikke.services.friendship.application.port

import jp.xhw.mikke.services.friendship.model.BlockRelation
import jp.xhw.mikke.services.friendship.model.UserId

interface BlockRepository {
    fun save(block: BlockRelation)

    fun delete(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): Boolean

    fun find(
        blockerUserId: UserId,
        blockedUserId: UserId,
    ): BlockRelation?
}
