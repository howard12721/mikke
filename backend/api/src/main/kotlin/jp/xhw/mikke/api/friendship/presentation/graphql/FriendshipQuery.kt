package jp.xhw.mikke.api.friendship.presentation.graphql

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.common.presentation.graphql.PageInput
import jp.xhw.mikke.api.common.presentation.graphql.toApplication
import jp.xhw.mikke.api.common.presentation.graphql.toGraphQl
import jp.xhw.mikke.api.friendship.application.FriendshipApiService
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.user.application.UserApiService
import jp.xhw.mikke.api.user.presentation.graphql.loadGraphQlUsersById

class FriendshipQuery(
    private val friendshipApiService: FriendshipApiService,
    private val userApiService: UserApiService,
) : Query {
    suspend fun friendship(
        targetUserId: String,
        environment: DataFetchingEnvironment,
    ): FriendshipSummary = friendshipApiService.getFriendship(environment.apiRequestContext(), targetUserId).toGraphQl()

    suspend fun friends(
        targetUserId: String,
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): FriendPage {
        val result =
            friendshipApiService.listFriends(environment.apiRequestContext(), targetUserId, page.toApplication())
        val usersById = userApiService.loadGraphQlUsersById(environment, result.items)
        return FriendPage(
            users = result.items.map { usersById.getValue(it) },
            pageInfo = result.pageInfo.toGraphQl(),
        )
    }

    suspend fun incomingFriendRequests(
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): FriendRequestPage {
        val result = friendshipApiService.incomingRequests(environment.apiRequestContext(), page.toApplication())
        val usersById =
            userApiService.loadGraphQlUsersById(
                environment,
                result.items.flatMap { listOf(it.senderUserId, it.receiverUserId) },
            )
        return FriendRequestPage(
            requests = result.items.map { it.toGraphQl(usersById) },
            pageInfo = result.pageInfo.toGraphQl(),
        )
    }

    suspend fun outgoingFriendRequests(
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): FriendRequestPage {
        val result = friendshipApiService.outgoingRequests(environment.apiRequestContext(), page.toApplication())
        val usersById =
            userApiService.loadGraphQlUsersById(
                environment,
                result.items.flatMap { listOf(it.senderUserId, it.receiverUserId) },
            )
        return FriendRequestPage(
            requests = result.items.map { it.toGraphQl(usersById) },
            pageInfo = result.pageInfo.toGraphQl(),
        )
    }
}
