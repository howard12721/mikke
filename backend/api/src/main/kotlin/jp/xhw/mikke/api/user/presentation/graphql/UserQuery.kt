package jp.xhw.mikke.api.user.presentation.graphql

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.common.presentation.graphql.PageInput
import jp.xhw.mikke.api.common.presentation.graphql.toApplication
import jp.xhw.mikke.api.common.presentation.graphql.toGraphQl
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.user.application.UserApiService

class UserQuery(
    private val userApiService: UserApiService,
) : Query {
    suspend fun me(environment: DataFetchingEnvironment): CurrentUser = userApiService.me(environment.apiRequestContext()).toGraphQl()

    suspend fun user(
        id: String,
        environment: DataFetchingEnvironment,
    ): User = userApiService.getUser(environment.apiRequestContext(), id).toGraphQl()

    suspend fun searchUsers(
        query: String,
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): UserSearchPayload {
        val result = userApiService.searchUsers(environment.apiRequestContext(), query, page.toApplication())
        return UserSearchPayload(
            users = result.items.map { it.toGraphQl() },
            pageInfo = result.pageInfo.toGraphQl(),
        )
    }
}
