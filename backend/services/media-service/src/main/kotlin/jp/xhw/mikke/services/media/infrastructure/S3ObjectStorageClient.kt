package jp.xhw.mikke.services.media.infrastructure

import jp.xhw.mikke.platform.time.toKotlinInstant
import jp.xhw.mikke.services.media.application.*
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import kotlin.time.Duration
import kotlin.time.toJavaDuration

data class ObjectStorageConfig(
    val endpoint: URI,
    val publicEndpoint: URI,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val forcePathStyle: Boolean,
) {
    companion object {
        fun fromEnv(): ObjectStorageConfig {
            val endpoint = requireEnv("OBJECT_STORAGE_ENDPOINT")
            val publicEndpoint = System.getenv("OBJECT_STORAGE_PUBLIC_ENDPOINT") ?: endpoint
            return ObjectStorageConfig(
                endpoint = URI.create(endpoint),
                publicEndpoint = URI.create(publicEndpoint),
                region = System.getenv("OBJECT_STORAGE_REGION") ?: "garage",
                bucket = requireEnv("OBJECT_STORAGE_BUCKET"),
                accessKeyId = requireEnv("OBJECT_STORAGE_ACCESS_KEY_ID"),
                secretAccessKey = requireEnv("OBJECT_STORAGE_SECRET_ACCESS_KEY"),
                forcePathStyle = System.getenv("OBJECT_STORAGE_FORCE_PATH_STYLE")?.toBooleanStrictOrNull() ?: true,
            )
        }

        private fun requireEnv(name: String): String =
            System.getenv(name)?.takeIf { it.isNotEmpty() }
                ?: error("$name is required")
    }
}

class S3ObjectStorageClient(
    private val config: ObjectStorageConfig,
    private val s3Client: S3Client = createS3Client(config),
    private val presigner: S3Presigner = createPresigner(config),
) : ObjectStorageClient {
    override fun createPresignedPutUrl(
        objectKey: String,
        contentType: String,
        contentLengthBytes: Long,
        expiresIn: Duration,
    ): PresignedUpload {
        val putObjectRequest =
            PutObjectRequest
                .builder()
                .bucket(config.bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLengthBytes)
                .build()

        val presignedRequest =
            PutObjectPresignRequest
                .builder()
                .signatureDuration(expiresIn.toJavaDuration())
                .putObjectRequest(putObjectRequest)
                .build()

        val presigned = presigner.presignPutObject(presignedRequest)
        val requiredHeaders = linkedMapOf("Content-Type" to contentType)

        return PresignedUpload(
            uploadUrl = presigned.url().toString(),
            requiredHeaders = requiredHeaders,
            expiresAt = presigned.expiration().toKotlinInstant(),
        )
    }

    override fun createPresignedGetUrl(
        objectKey: String,
        expiresIn: Duration,
    ): PresignedDownload {
        val getObjectRequest =
            GetObjectRequest
                .builder()
                .bucket(config.bucket)
                .key(objectKey)
                .build()
        val presignedRequest =
            GetObjectPresignRequest
                .builder()
                .signatureDuration(expiresIn.toJavaDuration())
                .getObjectRequest(getObjectRequest)
                .build()
        val presigned = presigner.presignGetObject(presignedRequest)

        return PresignedDownload(
            url = presigned.url().toString(),
            expiresAt = presigned.expiration().toKotlinInstant(),
        )
    }

    override fun headObject(objectKey: String): StoredObjectMetadata? {
        val request =
            HeadObjectRequest
                .builder()
                .bucket(config.bucket)
                .key(objectKey)
                .build()

        return try {
            val response = s3Client.headObject(request)
            StoredObjectMetadata(
                contentLengthBytes = response.contentLength(),
                contentType = response.contentType() ?: "application/octet-stream",
                etag = response.eTag()?.trim('"').orEmpty(),
            )
        } catch (_: NoSuchKeyException) {
            null
        } catch (e: AwsServiceException) {
            if (e.statusCode() == 404) {
                null
            } else {
                throw e
            }
        }
    }

    override fun getObject(
        objectKey: String,
        maxContentLengthBytes: Long?,
    ): StoredObject? {
        val request =
            GetObjectRequest
                .builder()
                .bucket(config.bucket)
                .key(objectKey)
                .build()

        return try {
            s3Client.getObject(request).use { response ->
                val declaredLength = response.response().contentLength()
                if (maxContentLengthBytes != null && declaredLength > maxContentLengthBytes) {
                    throw ObjectTooLargeException(objectKey, declaredLength, maxContentLengthBytes)
                }

                val bytes =
                    if (maxContentLengthBytes == null) {
                        response.readAllBytes()
                    } else {
                        response.readBounded(objectKey, maxContentLengthBytes)
                    }
                StoredObject(
                    bytes = bytes,
                    metadata =
                        StoredObjectMetadata(
                            contentLengthBytes = bytes.size.toLong(),
                            contentType = response.response().contentType() ?: "application/octet-stream",
                            etag =
                                response
                                    .response()
                                    .eTag()
                                    ?.trim('"')
                                    .orEmpty(),
                        ),
                )
            }
        } catch (_: NoSuchKeyException) {
            null
        } catch (e: AwsServiceException) {
            if (e.statusCode() == 404) {
                null
            } else {
                throw e
            }
        }
    }

    override fun putObject(
        objectKey: String,
        contentType: String,
        bytes: ByteArray,
    ): StoredObjectMetadata {
        val request =
            PutObjectRequest
                .builder()
                .bucket(config.bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(bytes.size.toLong())
                .build()
        val response = s3Client.putObject(request, RequestBody.fromBytes(bytes))

        return StoredObjectMetadata(
            contentLengthBytes = bytes.size.toLong(),
            contentType = contentType,
            etag =
                response
                    .eTag()
                    ?.trim('"')
                    .orEmpty(),
        )
    }

    private companion object {
        fun createS3Client(config: ObjectStorageConfig): S3Client =
            S3Client
                .builder()
                .endpointOverride(config.endpoint)
                .region(Region.of(config.region))
                .credentialsProvider(credentialsProvider(config))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(config.forcePathStyle).build(),
                ).build()

        fun createPresigner(config: ObjectStorageConfig): S3Presigner =
            S3Presigner
                .builder()
                .endpointOverride(config.publicEndpoint)
                .region(Region.of(config.region))
                .credentialsProvider(credentialsProvider(config))
                .serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(config.forcePathStyle).build(),
                ).build()

        fun credentialsProvider(config: ObjectStorageConfig) =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey),
            )
    }
}

private fun java.io.InputStream.readBounded(
    objectKey: String,
    maxContentLengthBytes: Long,
): ByteArray {
    require(maxContentLengthBytes < Int.MAX_VALUE) { "maxContentLengthBytes must fit into memory-backed object reads" }
    val limit = maxContentLengthBytes.toInt()
    val bytes = readNBytes(limit + 1)
    if (bytes.size > limit) {
        throw ObjectTooLargeException(objectKey, bytes.size.toLong(), maxContentLengthBytes)
    }
    return bytes
}
