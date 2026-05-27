package jp.xhw.mikke.services.guess.infrastructure

import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.post.v1.CheckPostVisibilityRequest
import jp.xhw.mikke.post.v1.GetPostLocationForGuessRequest
import jp.xhw.mikke.post.v1.PostServiceGrpcKt
import jp.xhw.mikke.services.guess.application.PostAccessPort
import jp.xhw.mikke.services.guess.application.PostLocationForGuess
import jp.xhw.mikke.services.guess.model.GeoPoint
import jp.xhw.mikke.services.guess.model.PostId
import jp.xhw.mikke.services.guess.model.UserId

class GrpcPostAccessAdapter(
    private val postStub: PostServiceGrpcKt.PostServiceCoroutineStub,
) : PostAccessPort {
    override suspend fun canViewPost(
        postId: PostId,
        viewerUserId: UserId,
    ): Boolean {
        val response =
            postStub.checkPostVisibility(
                CheckPostVisibilityRequest
                    .newBuilder()
                    .setPostId(postId.value.toString())
                    .setActor(viewerUserId.toActorProto())
                    .build(),
            )
        return response.canView
    }

    override suspend fun getPostLocationForGuess(postId: PostId): PostLocationForGuess {
        val response =
            postStub.getPostLocationForGuess(
                GetPostLocationForGuessRequest
                    .newBuilder()
                    .setPostId(postId.value.toString())
                    .build(),
            )

        return PostLocationForGuess(
            postId = PostId(parseGrpcUuid(response.postId, "post_id")),
            authorUserId = UserId(parseGrpcUuid(response.authorUserId, "author_user_id")),
            location =
                GeoPoint(
                    latitude = response.location.latitude,
                    longitude = response.location.longitude,
                ),
        )
    }
}

private fun UserId.toActorProto(): ActorContext =
    ActorContext
        .newBuilder()
        .setUserId(value.toString())
        .build()
