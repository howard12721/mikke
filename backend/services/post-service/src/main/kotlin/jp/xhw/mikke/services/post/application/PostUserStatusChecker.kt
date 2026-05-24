package jp.xhw.mikke.services.post.application

import jp.xhw.mikke.services.post.model.UserId

interface PostUserStatusChecker {
    suspend fun requireActiveUser(userId: UserId)

    suspend fun filterActiveUsers(userIds: Collection<UserId>): Set<UserId>
}
