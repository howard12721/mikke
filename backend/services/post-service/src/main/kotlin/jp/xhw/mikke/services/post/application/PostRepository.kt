package jp.xhw.mikke.services.post.application

import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.services.post.model.Post
import jp.xhw.mikke.services.post.model.PostId
import jp.xhw.mikke.services.post.model.UserId
import kotlin.time.Instant

interface PostRepository {
    fun save(post: Post)

    fun findById(id: PostId): Post?

    fun findByIds(ids: List<PostId>): List<Post>

    fun softDelete(
        id: PostId,
        deletedAt: Instant,
    ): Post?

    fun updateCaption(
        id: PostId,
        caption: String,
        updatedAt: Instant,
    ): Post?

    fun listByAuthor(
        authorUserId: UserId,
        includeDeleted: Boolean,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Post>

    fun listActiveFriendsPosts(
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Post>
}
