package jp.xhw.mikke.api.user.presentation.graphql

import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.user.application.UserApiService

suspend fun UserApiService.loadGraphQlUsersById(
    environment: DataFetchingEnvironment,
    userIds: List<String>,
): Map<String, User> =
    batchGetUsersByIdMap(environment.apiRequestContext(), userIds)
        .mapValues { (_, user) -> user.toGraphQl() }
