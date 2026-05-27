package jp.xhw.mikke.api.post.presentation.graphql

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.common.presentation.graphql.PageInput
import jp.xhw.mikke.api.common.presentation.graphql.toApplication
import jp.xhw.mikke.api.common.presentation.graphql.toGraphQl
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.post.application.PostApiService

class PostQuery(
    private val postApiService: PostApiService,
) : Query {
    suspend fun post(
        id: String,
        environment: DataFetchingEnvironment,
    ): TimelineItem = postApiService.postDetail(environment.apiRequestContext(), id).toGraphQl()

    suspend fun myPosts(
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): TimelinePage {
        val result = postApiService.myPosts(environment.apiRequestContext(), page.toApplication())
        return TimelinePage(items = result.items.map { it.toGraphQl() }, pageInfo = result.pageInfo.toGraphQl())
    }

    suspend fun timeline(
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): TimelinePage {
        val result = postApiService.timeline(environment.apiRequestContext(), page.toApplication())
        return TimelinePage(items = result.items.map { it.toGraphQl() }, pageInfo = result.pageInfo.toGraphQl())
    }
}
