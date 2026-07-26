package jp.xhw.mikke.services.notification.infrastructure

import io.grpc.ManagedChannel
import jp.xhw.mikke.common.v1.ActorContext
import jp.xhw.mikke.common.v1.PageRequest
import jp.xhw.mikke.friendship.v1.FriendshipServiceGrpcKt
import jp.xhw.mikke.friendship.v1.ListFriendsRequest
import jp.xhw.mikke.platform.grpc.InternalCallerClientInterceptor
import jp.xhw.mikke.platform.grpc.grpcClientChannelFromEnvironment
import jp.xhw.mikke.platform.uuid.parseGrpcUuid
import jp.xhw.mikke.services.notification.worker.FriendshipReader
import kotlin.uuid.Uuid

private const val FRIENDS_PAGE_SIZE = 100

class GrpcFriendshipReader(
    private val channel: ManagedChannel,
    private val stub: FriendshipServiceGrpcKt.FriendshipServiceCoroutineStub =
        FriendshipServiceGrpcKt
            .FriendshipServiceCoroutineStub(channel)
            .withInterceptors(InternalCallerClientInterceptor(serviceName = "notification-service")),
) : FriendshipReader,
    AutoCloseable {
    override suspend fun listFriendUserIds(userId: Uuid): List<Uuid> {
        val friendIds = linkedSetOf<Uuid>()
        var pageToken = ""
        val seenPageTokens = mutableSetOf<String>()

        do {
            val response =
                stub.listFriends(
                    ListFriendsRequest
                        .newBuilder()
                        .setTargetUserId(userId.toString())
                        .setActor(
                            ActorContext
                                .newBuilder()
                                .setUserId(userId.toString())
                                .build(),
                        ).setPage(
                            PageRequest
                                .newBuilder()
                                .setPageSize(FRIENDS_PAGE_SIZE)
                                .setPageToken(pageToken)
                                .build(),
                        ).build(),
                )
            response.friendUserIdsList
                .mapTo(friendIds) { rawId -> parseGrpcUuid(rawId, "friend_user_id") }

            if (!response.pageInfo.hasNextPage) break
            pageToken = response.pageInfo.nextPageToken
            check(pageToken.isNotBlank() && seenPageTokens.add(pageToken)) {
                "Friendship service returned an invalid pagination token"
            }
        } while (true)

        return friendIds.toList()
    }

    override fun close() {
        channel.shutdownNow()
    }

    companion object {
        fun fromEnvironment(): GrpcFriendshipReader =
            GrpcFriendshipReader(
                grpcClientChannelFromEnvironment(
                    targetEnv = "FRIENDSHIP_SERVICE_TARGET",
                    hostEnv = "FRIENDSHIP_SERVICE_HOST",
                    portEnv = "FRIENDSHIP_SERVICE_PORT",
                    defaultHost = "friendship-service",
                    defaultPort = 50052,
                ),
            )
    }
}
