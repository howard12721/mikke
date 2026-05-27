package jp.xhw.mikke.api.graphql

import com.expediagroup.graphql.server.ktor.GraphQL
import com.expediagroup.graphql.server.operations.Mutation
import com.expediagroup.graphql.server.operations.Query
import io.ktor.server.application.*
import jp.xhw.mikke.api.auth.presentation.graphql.AuthMutation
import jp.xhw.mikke.api.bootstrap.ApiDependencies
import jp.xhw.mikke.api.friendship.presentation.graphql.FriendshipMutation
import jp.xhw.mikke.api.friendship.presentation.graphql.FriendshipQuery
import jp.xhw.mikke.api.guess.presentation.graphql.GuessMutation
import jp.xhw.mikke.api.guess.presentation.graphql.GuessQuery
import jp.xhw.mikke.api.media.presentation.graphql.MediaMutation
import jp.xhw.mikke.api.post.presentation.graphql.PostMutation
import jp.xhw.mikke.api.post.presentation.graphql.PostQuery
import jp.xhw.mikke.api.user.presentation.graphql.UserMutation
import jp.xhw.mikke.api.user.presentation.graphql.UserQuery
import kotlin.reflect.KClass

fun Application.configureApiGraphQl(dependencies: ApiDependencies) {
    install(GraphQL) {
        schema {
            packages = apiGraphQlPackages
            queries = apiGraphQlQueries(dependencies)
            mutations = apiGraphQlMutations(dependencies)
        }
        engine {
            exceptionHandler = ApiGraphQlExceptionHandler()
        }
        server {
            contextFactory =
                ApiGraphQlContextFactory(
                    sessionAuthenticator = dependencies.sessionAuthenticator,
                    touchScheduler = dependencies.touchScheduler,
                )
        }
    }
}

val apiGraphQlPackages =
    listOf(
        "jp.xhw.mikke.api.graphql",
        "jp.xhw.mikke.api.auth.presentation.graphql",
        "jp.xhw.mikke.api.common.presentation.graphql",
        "jp.xhw.mikke.api.user.presentation.graphql",
        "jp.xhw.mikke.api.media.presentation.graphql",
        "jp.xhw.mikke.api.post.presentation.graphql",
        "jp.xhw.mikke.api.friendship.presentation.graphql",
        "jp.xhw.mikke.api.guess.presentation.graphql",
    )

fun apiGraphQlQueries(dependencies: ApiDependencies): List<Query> =
    listOf(
        ApiQuery(),
        UserQuery(userApiService = dependencies.userApiService),
        PostQuery(postApiService = dependencies.postApiService),
        FriendshipQuery(
            friendshipApiService = dependencies.friendshipApiService,
            userApiService = dependencies.userApiService,
        ),
        GuessQuery(
            guessApiService = dependencies.guessApiService,
            userApiService = dependencies.userApiService,
        ),
    )

fun apiGraphQlMutations(dependencies: ApiDependencies): List<Mutation> =
    listOf(
        AuthMutation(authApiService = dependencies.authApiService),
        UserMutation(userApiService = dependencies.userApiService),
        MediaMutation(mediaApiService = dependencies.mediaApiService),
        PostMutation(postApiService = dependencies.postApiService),
        FriendshipMutation(
            friendshipApiService = dependencies.friendshipApiService,
            userApiService = dependencies.userApiService,
        ),
        GuessMutation(guessApiService = dependencies.guessApiService),
    )

fun apiGraphQlQueryTypes(): List<KClass<*>> =
    listOf(
        ApiQuery::class,
        UserQuery::class,
        PostQuery::class,
        FriendshipQuery::class,
        GuessQuery::class,
    )

fun apiGraphQlMutationTypes(): List<KClass<*>> =
    listOf(
        AuthMutation::class,
        UserMutation::class,
        MediaMutation::class,
        PostMutation::class,
        FriendshipMutation::class,
        GuessMutation::class,
    )

class ApiQuery : Query {
    fun health(): String = "ok"
}
