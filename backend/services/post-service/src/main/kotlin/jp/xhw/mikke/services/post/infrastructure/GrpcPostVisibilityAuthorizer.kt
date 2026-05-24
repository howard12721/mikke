package jp.xhw.mikke.services.post.infrastructure

import io.grpc.Status
import io.grpc.StatusException
import jp.xhw.mikke.friendship.v1.CheckCanViewUserPostsForViewerRequest
import jp.xhw.mikke.friendship.v1.FriendshipServiceGrpcKt
import jp.xhw.mikke.services.post.application.PostVisibilityAuthorizer
import jp.xhw.mikke.services.post.model.UserId

class GrpcPostVisibilityAuthorizer(
    private val friendshipService: FriendshipServiceGrpcKt.FriendshipServiceCoroutineStub,
) : PostVisibilityAuthorizer {
    override suspend fun canViewFriendPosts(
        viewerUserId: UserId,
        authorUserId: UserId,
    ): Boolean {
        if (viewerUserId == authorUserId) {
            return true
        }

        return try {
            friendshipService
                .checkCanViewUserPostsForViewer(
                    CheckCanViewUserPostsForViewerRequest
                        .newBuilder()
                        .setViewerUserId(viewerUserId.value.toString())
                        .setOwnerUserId(authorUserId.value.toString())
                        .build(),
                ).canView
        } catch (e: StatusException) {
            if (e.status.code == Status.Code.NOT_FOUND) {
                false
            } else {
                throw e
            }
        }
    }
}
