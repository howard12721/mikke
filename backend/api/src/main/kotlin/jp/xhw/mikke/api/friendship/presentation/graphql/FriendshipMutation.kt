package jp.xhw.mikke.api.friendship.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.friendship.application.FriendshipApiService
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.user.application.UserApiService
import jp.xhw.mikke.api.user.presentation.graphql.loadGraphQlUsersById

class FriendshipMutation(
    private val friendshipApiService: FriendshipApiService,
    private val userApiService: UserApiService,
) : Mutation {
    suspend fun sendFriendRequest(
        input: SendFriendRequestInput,
        environment: DataFetchingEnvironment,
    ): FriendRequest {
        val request = friendshipApiService.sendRequest(environment.apiRequestContext(), input.receiverUserId)
        return request.toGraphQl(
            userApiService.loadGraphQlUsersById(environment, listOf(request.senderUserId, request.receiverUserId)),
        )
    }

    suspend fun acceptFriendRequest(
        input: FriendRequestIdInput,
        environment: DataFetchingEnvironment,
    ): Friendship = friendshipApiService.acceptRequest(environment.apiRequestContext(), input.friendRequestId).toGraphQl()

    suspend fun rejectFriendRequest(
        input: FriendRequestIdInput,
        environment: DataFetchingEnvironment,
    ): FriendRequest {
        val request = friendshipApiService.rejectRequest(environment.apiRequestContext(), input.friendRequestId)
        return request.toGraphQl(
            userApiService.loadGraphQlUsersById(environment, listOf(request.senderUserId, request.receiverUserId)),
        )
    }

    suspend fun cancelFriendRequest(
        input: FriendRequestIdInput,
        environment: DataFetchingEnvironment,
    ): FriendRequest {
        val request = friendshipApiService.cancelRequest(environment.apiRequestContext(), input.friendRequestId)
        return request.toGraphQl(
            userApiService.loadGraphQlUsersById(environment, listOf(request.senderUserId, request.receiverUserId)),
        )
    }

    suspend fun removeFriend(
        input: UserIdInput,
        environment: DataFetchingEnvironment,
    ): BooleanPayload {
        friendshipApiService.removeFriend(environment.apiRequestContext(), input.userId)
        return BooleanPayload(success = true)
    }

    suspend fun blockUser(
        input: UserIdInput,
        environment: DataFetchingEnvironment,
    ): BlockRelation = friendshipApiService.blockUser(environment.apiRequestContext(), input.userId).toGraphQl()

    suspend fun unblockUser(
        input: UserIdInput,
        environment: DataFetchingEnvironment,
    ): BooleanPayload {
        friendshipApiService.unblockUser(environment.apiRequestContext(), input.userId)
        return BooleanPayload(success = true)
    }
}
