package jp.xhw.mikke.api.friendship.presentation.graphql

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.common.presentation.graphql.PageInput
import jp.xhw.mikke.api.common.presentation.graphql.toApplication
import jp.xhw.mikke.api.common.presentation.graphql.toGraphQl
import jp.xhw.mikke.api.friendship.application.FriendshipApiService
import jp.xhw.mikke.api.graphql.apiRequestContext

class FriendshipQuery(
    private val friendshipApiService: FriendshipApiService,
) : Query {
    suspend fun friendship(
        targetUserId: String,
        environment: DataFetchingEnvironment,
    ): FriendshipSummary = friendshipApiService.getFriendship(environment.apiRequestContext(), targetUserId).toGraphQl()

    suspend fun friends(
        targetUserId: String,
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): FriendUserIdsPage {
        val result =
            friendshipApiService.listFriends(environment.apiRequestContext(), targetUserId, page.toApplication())
        return FriendUserIdsPage(userIds = result.items, pageInfo = result.pageInfo.toGraphQl())
    }

    suspend fun incomingFriendRequests(
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): FriendRequestPage {
        val result = friendshipApiService.incomingRequests(environment.apiRequestContext(), page.toApplication())
        return FriendRequestPage(requests = result.items.map { it.toGraphQl() }, pageInfo = result.pageInfo.toGraphQl())
    }

    suspend fun outgoingFriendRequests(
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): FriendRequestPage {
        val result = friendshipApiService.outgoingRequests(environment.apiRequestContext(), page.toApplication())
        return FriendRequestPage(requests = result.items.map { it.toGraphQl() }, pageInfo = result.pageInfo.toGraphQl())
    }
}
