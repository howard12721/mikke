package jp.xhw.mikke.services.post.infrastructure

import jp.xhw.mikke.platform.database.exposed.isUniqueConstraintViolation
import jp.xhw.mikke.platform.pagination.CreatedAtIdCursor
import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.post.application.DuplicatePostMediaException
import jp.xhw.mikke.services.post.application.PostRepository
import jp.xhw.mikke.services.post.model.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Instant

class ExposedPostRepository : PostRepository {
    override fun save(post: Post) {
        try {
            PostsTable.insert { row ->
                row[id] = post.id.value
                row[authorUserId] = post.authorUserId.value
                row[mediaId] = post.mediaId.value
                row[caption] = post.caption
                row[visibility] = post.visibility.name
                row[status] = post.status.name
                row[latitude] = post.location.latitude.toBigDecimal()
                row[longitude] = post.location.longitude.toBigDecimal()
                row[accuracyMeters] = post.location.accuracyMeters
                row[createdAt] = post.createdAt.toJavaInstant()
                row[updatedAt] = post.updatedAt.toJavaInstant()
                row[deletedAt] = post.deletedAt?.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueConstraintViolation()) {
                throw DuplicatePostMediaException(cause = e)
            }
            throw e
        }
    }

    override fun findById(id: PostId): Post? =
        PostsTable
            .selectAll()
            .where { PostsTable.id eq id.value }
            .limit(1)
            .singleOrNull()
            ?.toPost()

    override fun findByIds(ids: List<PostId>): List<Post> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        val byId =
            PostsTable
                .selectAll()
                .where { PostsTable.id inList ids.map { it.value }.distinct() }
                .map { it.toPost() }
                .associateBy { it.id.value.toString() }

        return ids.mapNotNull { byId[it.value.toString()] }
    }

    override fun softDelete(
        id: PostId,
        deletedAt: Instant,
    ): Post? {
        val updated =
            PostsTable.update({ (PostsTable.id eq id.value) and (PostsTable.status eq PostStatus.ACTIVE.name) }) {
                it[status] = PostStatus.DELETED.name
                it[PostsTable.deletedAt] = deletedAt.toJavaInstant()
                it[updatedAt] = deletedAt.toJavaInstant()
            }

        if (updated == 0) {
            return null
        }

        return findById(id)
    }

    override fun updateCaption(
        id: PostId,
        caption: String,
        updatedAt: Instant,
    ): Post? {
        val updated =
            PostsTable.update({ (PostsTable.id eq id.value) and (PostsTable.status eq PostStatus.ACTIVE.name) }) {
                it[PostsTable.caption] = caption
                it[PostsTable.updatedAt] = updatedAt.toJavaInstant()
            }

        if (updated == 0) {
            return null
        }

        return findById(id)
    }

    override fun updateVisibility(
        id: PostId,
        visibility: PostVisibility,
        updatedAt: Instant,
    ): Post? {
        val updated =
            PostsTable.update({ (PostsTable.id eq id.value) and (PostsTable.status eq PostStatus.ACTIVE.name) }) {
                it[PostsTable.visibility] = visibility.name
                it[PostsTable.updatedAt] = updatedAt.toJavaInstant()
            }

        if (updated == 0) {
            return null
        }

        return findById(id)
    }

    override fun listByAuthor(
        authorUserId: UserId,
        includeDeleted: Boolean,
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Post> {
        val condition =
            buildList {
                add(PostsTable.authorUserId eq authorUserId.value)
                if (!includeDeleted) {
                    add(PostsTable.status eq PostStatus.ACTIVE.name)
                }
                cursor?.let {
                    add(
                        (PostsTable.createdAt less it.createdAt.toJavaInstant()) or
                            ((PostsTable.createdAt eq it.createdAt.toJavaInstant()) and (PostsTable.id less it.id)),
                    )
                }
            }.reduce { acc, op -> acc and op }

        return PostsTable
            .selectAll()
            .where { condition }
            .orderBy(PostsTable.createdAt to SortOrder.DESC, PostsTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toPost() }
    }

    override fun listActiveFriendsPosts(
        limit: Int,
        cursor: CreatedAtIdCursor?,
    ): List<Post> {
        val condition =
            buildList {
                add(PostsTable.status eq PostStatus.ACTIVE.name)
                add(PostsTable.visibility eq PostVisibility.FRIENDS.name)
                cursor?.let {
                    add(
                        (PostsTable.createdAt less it.createdAt.toJavaInstant()) or
                            ((PostsTable.createdAt eq it.createdAt.toJavaInstant()) and (PostsTable.id less it.id)),
                    )
                }
            }.reduce { acc, op -> acc and op }

        return PostsTable
            .selectAll()
            .where { condition }
            .orderBy(PostsTable.createdAt to SortOrder.DESC, PostsTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toPost() }
    }
}

private object PostsTable : Table("posts") {
    val id = uuidBinary("id")
    val authorUserId = uuidBinary("author_user_id")
    val mediaId = uuidBinary("media_id")
    val caption = varchar("caption", length = 500)
    val visibility = varchar("visibility", length = 32)
    val status = varchar("status", length = 32)
    val latitude = decimal("latitude", precision = 9, scale = 6)
    val longitude = decimal("longitude", precision = 9, scale = 6)
    val accuracyMeters = double("accuracy_meters")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

private fun ResultRow.toPost(): Post =
    Post(
        id = PostId(this[PostsTable.id]),
        authorUserId = UserId(this[PostsTable.authorUserId]),
        mediaId = MediaId(this[PostsTable.mediaId]),
        caption = this[PostsTable.caption],
        visibility = PostVisibility.valueOf(this[PostsTable.visibility]),
        status = PostStatus.valueOf(this[PostsTable.status]),
        location =
            PostLocation(
                latitude = this[PostsTable.latitude].toDoubleValue(),
                longitude = this[PostsTable.longitude].toDoubleValue(),
                accuracyMeters = this[PostsTable.accuracyMeters],
            ),
        createdAt = this[PostsTable.createdAt].toKotlinInstant(),
        updatedAt = this[PostsTable.updatedAt].toKotlinInstant(),
        deletedAt = this[PostsTable.deletedAt]?.toKotlinInstant(),
    )

private fun Double.toBigDecimal(): BigDecimal = BigDecimal.valueOf(this)

private fun BigDecimal.toDoubleValue(): Double = toDouble()
