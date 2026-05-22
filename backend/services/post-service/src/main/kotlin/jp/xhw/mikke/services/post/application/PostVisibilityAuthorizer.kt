package jp.xhw.mikke.services.post.application

import jp.xhw.mikke.services.post.model.UserId

interface PostVisibilityAuthorizer {
    suspend fun canViewFriendPosts(
        viewerUserId: UserId,
        authorUserId: UserId,
    ): Boolean
}
