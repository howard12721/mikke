package jp.xhw.mikke.api.friendship.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.friendship.application.FriendshipApiService
import jp.xhw.mikke.api.graphql.apiRequestContext

class FriendshipMutation(
    private val friendshipApiService: FriendshipApiService,
) : Mutation {
    suspend fun sendFriendRequest(
        input: SendFriendRequestInput,
        environment: DataFetchingEnvironment,
    ): FriendRequest = friendshipApiService.sendRequest(environment.apiRequestContext(), input.receiverUserId).toGraphQl()

    suspend fun acceptFriendRequest(
        input: FriendRequestIdInput,
        environment: DataFetchingEnvironment,
    ): Friendship = friendshipApiService.acceptRequest(environment.apiRequestContext(), input.friendRequestId).toGraphQl()

    suspend fun rejectFriendRequest(
        input: FriendRequestIdInput,
        environment: DataFetchingEnvironment,
    ): FriendRequest = friendshipApiService.rejectRequest(environment.apiRequestContext(), input.friendRequestId).toGraphQl()

    suspend fun cancelFriendRequest(
        input: FriendRequestIdInput,
        environment: DataFetchingEnvironment,
    ): FriendRequest = friendshipApiService.cancelRequest(environment.apiRequestContext(), input.friendRequestId).toGraphQl()

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
