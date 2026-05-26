package jp.xhw.mikke.api.guess.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.common.presentation.graphql.toApplication
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.guess.application.GuessApiService

class GuessMutation(
    private val guessApiService: GuessApiService,
) : Mutation {
    suspend fun submitGuess(
        input: SubmitGuessInput,
        environment: DataFetchingEnvironment,
    ): GuessResult =
        guessApiService
            .submitGuess(
                context = environment.apiRequestContext(),
                postId = input.postId,
                guessedPoint = input.guessedPoint.toApplication(),
            ).toGraphQl()
}
