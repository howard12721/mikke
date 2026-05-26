package jp.xhw.mikke.api.guess.presentation.graphql

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.common.presentation.graphql.PageInput
import jp.xhw.mikke.api.common.presentation.graphql.toApplication
import jp.xhw.mikke.api.common.presentation.graphql.toGraphQl
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.guess.application.GuessApiService

class GuessQuery(
    private val guessApiService: GuessApiService,
) : Query {
    suspend fun myGuessForPost(
        postId: String,
        environment: DataFetchingEnvironment,
    ): GuessResult? = guessApiService.getMyGuessForPost(environment.apiRequestContext(), postId)?.toGraphQl()

    suspend fun postGuessStats(
        postId: String,
        environment: DataFetchingEnvironment,
    ): PostGuessStats = guessApiService.getPostGuessStats(environment.apiRequestContext(), postId).toGraphQl()

    suspend fun userScoreSummary(
        userId: String,
        environment: DataFetchingEnvironment,
    ): UserScoreSummary = guessApiService.getUserScoreSummary(environment.apiRequestContext(), userId).toGraphQl()

    suspend fun postRankings(
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): PostRankingPage {
        val result = guessApiService.listPostRankings(environment.apiRequestContext(), page.toApplication())
        return PostRankingPage(entries = result.items.map { it.toGraphQl() }, pageInfo = result.pageInfo.toGraphQl())
    }

    suspend fun guessRankings(
        metric: GuessRankingMetric,
        page: PageInput? = null,
        environment: DataFetchingEnvironment,
    ): GuessRankingPage {
        val result =
            guessApiService.listGuessRankings(environment.apiRequestContext(), metric.name, page.toApplication())
        return GuessRankingPage(entries = result.items.map { it.toGraphQl() }, pageInfo = result.pageInfo.toGraphQl())
    }
}
