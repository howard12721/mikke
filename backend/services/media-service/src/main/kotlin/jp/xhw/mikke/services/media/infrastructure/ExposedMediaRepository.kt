package jp.xhw.mikke.services.media.infrastructure

import jp.xhw.mikke.platform.time.toJavaInstant
import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.platform.uuid.exposed.uuidBinary
import jp.xhw.mikke.services.media.application.MediaRepository
import jp.xhw.mikke.services.media.model.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class ExposedMediaRepository : MediaRepository {
    override fun insert(media: MediaRecord) {
        MediaTable.insert { row ->
            row[id] = media.id.value
            row[uploaderUserId] = media.uploaderUserId.value
            row[storedObjectKey] = media.objectKey
            row[contentType] = media.contentType
            row[contentLengthBytes] = media.contentLengthBytes
            row[etag] = media.etag
            row[status] = media.status.toDatabaseValue()
            row[uploadMethod] = media.uploadMethod.toDatabaseValue()
            row[createdAt] = media.createdAt.toJavaInstant()
            row[uploadedAt] = media.uploadedAt?.toJavaInstant()
            row[deletedAt] = media.deletedAt?.toJavaInstant()
        }

        media.variants.forEach { variant ->
            MediaVariantsTable.insert { row ->
                row[MediaVariantsTable.id] = variant.id.value
                row[MediaVariantsTable.mediaId] = variant.mediaId.value
                row[MediaVariantsTable.variantKind] = variant.variant.toDatabaseValue()
                row[MediaVariantsTable.deliveryKey] = variant.deliveryKey
                row[MediaVariantsTable.storedObjectKey] = variant.objectKey
                row[MediaVariantsTable.status] = variant.status.toDatabaseValue()
                row[MediaVariantsTable.width] = variant.width
                row[MediaVariantsTable.height] = variant.height
                row[MediaVariantsTable.contentType] = variant.contentType
                row[MediaVariantsTable.contentLengthBytes] = variant.contentLengthBytes
                row[MediaVariantsTable.createdAt] = variant.createdAt.toJavaInstant()
                row[MediaVariantsTable.readyAt] = variant.readyAt?.toJavaInstant()
            }
        }
    }

    override fun findById(id: MediaId): MediaRecord? {
        val targetId = id.value
        return MediaTable
            .selectAll()
            .where { MediaTable.id eq targetId }
            .limit(1)
            .singleOrNull()
            ?.toMediaRecord(loadVariants = true)
    }

    override fun findByIds(ids: List<MediaId>): List<MediaRecord> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        val distinctIds = ids.map { it.value }.distinct()
        val mediaRows =
            MediaTable
                .selectAll()
                .where { MediaTable.id inList distinctIds }
                .map { it.toMediaRecord(loadVariants = false) }

        if (mediaRows.isEmpty()) {
            return emptyList()
        }

        val variantsByMediaId = loadVariantsForMediaIds(distinctIds)
        val byId =
            mediaRows
                .map { media ->
                    media.copy(variants = variantsByMediaId[media.id.value].orEmpty())
                }.associateBy { it.id.value }

        return ids.mapNotNull { byId[it.value] }
    }

    override fun findVariantByDeliveryKey(deliveryKey: String): MediaVariantRecord? =
        MediaVariantsTable
            .selectAll()
            .where { MediaVariantsTable.deliveryKey eq deliveryKey }
            .limit(1)
            .singleOrNull()
            ?.toMediaVariantRecord()

    override fun update(media: MediaRecord) {
        MediaTable.update({ MediaTable.id eq media.id.value }) { row ->
            row[uploaderUserId] = media.uploaderUserId.value
            row[storedObjectKey] = media.objectKey
            row[contentType] = media.contentType
            row[contentLengthBytes] = media.contentLengthBytes
            row[etag] = media.etag
            row[status] = media.status.toDatabaseValue()
            row[uploadMethod] = media.uploadMethod.toDatabaseValue()
            row[createdAt] = media.createdAt.toJavaInstant()
            row[uploadedAt] = media.uploadedAt?.toJavaInstant()
            row[deletedAt] = media.deletedAt?.toJavaInstant()
        }

        media.variants.forEach { variant ->
            MediaVariantsTable.update({
                (MediaVariantsTable.mediaId eq variant.mediaId.value) and
                    (MediaVariantsTable.variantKind eq variant.variant.toDatabaseValue())
            }) { row ->
                row[MediaVariantsTable.deliveryKey] = variant.deliveryKey
                row[MediaVariantsTable.storedObjectKey] = variant.objectKey
                row[MediaVariantsTable.status] = variant.status.toDatabaseValue()
                row[MediaVariantsTable.width] = variant.width
                row[MediaVariantsTable.height] = variant.height
                row[MediaVariantsTable.contentType] = variant.contentType
                row[MediaVariantsTable.contentLengthBytes] = variant.contentLengthBytes
                row[MediaVariantsTable.createdAt] = variant.createdAt.toJavaInstant()
                row[MediaVariantsTable.readyAt] = variant.readyAt?.toJavaInstant()
            }
        }
    }

    override fun findVariant(
        mediaId: MediaId,
        variant: MediaVariantKind,
    ): MediaVariantRecord? =
        MediaVariantsTable
            .selectAll()
            .where {
                (MediaVariantsTable.mediaId eq mediaId.value) and
                    (MediaVariantsTable.variantKind eq variant.toDatabaseValue())
            }.limit(1)
            .singleOrNull()
            ?.toMediaVariantRecord()

    private fun loadVariantsForMediaIds(mediaIds: List<Uuid>): Map<Uuid, List<MediaVariantRecord>> =
        MediaVariantsTable
            .selectAll()
            .where { MediaVariantsTable.mediaId inList mediaIds }
            .orderBy(MediaVariantsTable.variantKind to SortOrder.ASC)
            .map { it.toMediaVariantRecord() }
            .groupBy { it.mediaId.value }

    private fun ResultRow.toMediaRecord(loadVariants: Boolean): MediaRecord {
        val mediaId = MediaId(this[MediaTable.id])
        val variants =
            if (loadVariants) {
                loadVariantsForMediaIds(listOf(mediaId.value))[mediaId.value].orEmpty()
            } else {
                emptyList()
            }

        return MediaRecord(
            id = mediaId,
            uploaderUserId = UploaderUserId(this[MediaTable.uploaderUserId]),
            objectKey = this[MediaTable.storedObjectKey],
            contentType = this[MediaTable.contentType],
            contentLengthBytes = this[MediaTable.contentLengthBytes],
            etag = this[MediaTable.etag],
            status = MediaStatus.fromDatabaseValue(this[MediaTable.status]),
            uploadMethod = UploadMethod.fromDatabaseValue(this[MediaTable.uploadMethod]),
            createdAt = this[MediaTable.createdAt].toKotlinInstant(),
            uploadedAt = this[MediaTable.uploadedAt]?.toKotlinInstant(),
            deletedAt = this[MediaTable.deletedAt]?.toKotlinInstant(),
            variants = variants,
        )
    }

    private fun ResultRow.toMediaVariantRecord(): MediaVariantRecord =
        MediaVariantRecord(
            id = MediaVariantId(this[MediaVariantsTable.id]),
            mediaId = MediaId(this[MediaVariantsTable.mediaId]),
            variant = MediaVariantKind.fromDatabaseValue(this[MediaVariantsTable.variantKind]),
            deliveryKey = this[MediaVariantsTable.deliveryKey],
            objectKey = this[MediaVariantsTable.storedObjectKey],
            status = MediaVariantStatus.fromDatabaseValue(this[MediaVariantsTable.status]),
            width = this[MediaVariantsTable.width],
            height = this[MediaVariantsTable.height],
            contentType = this[MediaVariantsTable.contentType],
            contentLengthBytes = this[MediaVariantsTable.contentLengthBytes],
            createdAt = this[MediaVariantsTable.createdAt].toKotlinInstant(),
            readyAt = this[MediaVariantsTable.readyAt]?.toKotlinInstant(),
        )
}

private object MediaTable : Table("media") {
    val id = uuidBinary("id")
    val uploaderUserId = uuidBinary("uploader_user_id")
    val storedObjectKey = varchar("object_key", length = 512)
    val contentType = varchar("content_type", length = 128)
    val contentLengthBytes = long("content_length_bytes")
    val etag = varchar("etag", length = 255).nullable()
    val status = varchar("status", length = 32)
    val uploadMethod = varchar("upload_method", length = 16)
    val createdAt = timestamp("created_at")
    val uploadedAt = timestamp("uploaded_at").nullable()
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

private object MediaVariantsTable : Table("media_variants") {
    val id = uuidBinary("id")
    val mediaId = uuidBinary("media_id")
    val variantKind = varchar("variant", length = 32)
    val deliveryKey = varchar("delivery_key", length = 64)
    val storedObjectKey = varchar("object_key", length = 512)
    val status = varchar("status", length = 32)
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val contentType = varchar("content_type", length = 128)
    val contentLengthBytes = long("content_length_bytes").nullable()
    val createdAt = timestamp("created_at")
    val readyAt = timestamp("ready_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
