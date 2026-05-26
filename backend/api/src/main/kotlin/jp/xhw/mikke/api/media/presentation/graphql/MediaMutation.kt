package jp.xhw.mikke.api.media.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.media.application.MediaApiService

class MediaMutation(
    private val mediaApiService: MediaApiService,
) : Mutation {
    suspend fun createMediaUploadUrl(
        input: CreateMediaUploadInput,
        environment: DataFetchingEnvironment,
    ): MediaUploadUrl =
        mediaApiService
            .createUploadUrl(
                context = environment.apiRequestContext(),
                contentType = input.contentType,
                contentLengthBytes = input.contentLengthBytes.toLong(),
                originalFileName = input.originalFileName,
            ).toGraphQl()

    suspend fun checkMediaUpload(
        input: CheckMediaUploadInput,
        environment: DataFetchingEnvironment,
    ): UploadCheck =
        mediaApiService
            .checkUpload(
                context = environment.apiRequestContext(),
                mediaId = input.mediaId,
                objectKey = input.objectKey,
            ).toGraphQl()
}
