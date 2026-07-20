package jp.xhw.mikke.api.post.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.common.presentation.graphql.toApplication
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.post.application.PostApiService

class PostMutation(
    private val postApiService: PostApiService,
) : Mutation {
    suspend fun createPost(
        input: CreatePostInput,
        environment: DataFetchingEnvironment,
    ): Post =
        postApiService
            .createPost(
                context = environment.apiRequestContext(),
                mediaId = input.mediaId,
                caption = input.caption,
                location = input.location.toApplication(),
                accuracyMeters = input.accuracyMeters,
            ).toGraphQl()

    suspend fun updatePostCaption(
        input: UpdatePostCaptionInput,
        environment: DataFetchingEnvironment,
    ): Post =
        postApiService
            .updateCaption(
                context = environment.apiRequestContext(),
                postId = input.postId,
                caption = input.caption,
            ).toGraphQl()

    suspend fun deletePost(
        postId: String,
        environment: DataFetchingEnvironment,
    ): DeletePostPayload {
        postApiService.deletePost(environment.apiRequestContext(), postId)
        return DeletePostPayload(success = true)
    }
}
