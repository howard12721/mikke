package jp.xhw.mikke.services.post.infrastructure

import io.grpc.Status
import io.grpc.StatusException
import jp.xhw.mikke.identity.v1.BatchGetUsersRequest
import jp.xhw.mikke.identity.v1.IdentityServiceGrpcKt
import jp.xhw.mikke.identity.v1.UserStatus
import jp.xhw.mikke.services.post.application.PostUserStatusChecker
import jp.xhw.mikke.services.post.application.UserNotActiveException
import jp.xhw.mikke.services.post.model.UserId

class GrpcPostUserStatusChecker(
    private val identityService: IdentityServiceGrpcKt.IdentityServiceCoroutineStub,
) : PostUserStatusChecker {
    override suspend fun requireActiveUser(userId: UserId) {
        if (userId !in filterActiveUsers(listOf(userId))) {
            throw UserNotActiveException()
        }
    }

    override suspend fun filterActiveUsers(userIds: Collection<UserId>): Set<UserId> {
        if (userIds.isEmpty()) {
            return emptySet()
        }

        val response =
            try {
                identityService.batchGetUsers(
                    BatchGetUsersRequest
                        .newBuilder()
                        .addAllUserIds(userIds.map { it.value.toString() })
                        .build(),
                )
            } catch (e: StatusException) {
                if (e.status.code == Status.Code.NOT_FOUND) {
                    return emptySet()
                }
                throw e
            }

        return response.usersList
            .filter { it.status == UserStatus.USER_STATUS_ACTIVE }
            .map {
                UserId(
                    jp.xhw.mikke.platform.uuid
                        .parseGrpcUuid(it.id, "user_id"),
                )
            }.toSet()
    }
}
