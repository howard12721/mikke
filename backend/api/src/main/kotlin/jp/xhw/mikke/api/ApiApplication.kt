package jp.xhw.mikke.api

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import jp.xhw.mikke.api.bootstrap.ApiDependencies
import jp.xhw.mikke.api.graphql.configureApiGraphQl
import jp.xhw.mikke.api.http.configureApiErrorHandling
import jp.xhw.mikke.api.http.configureApiRouting

fun main() {
    apiServer().start(wait = true)
}

internal fun apiServer(port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080) =
    embeddedServer(factory = Netty, port = port, module = Application::apiModule)

fun Application.apiModule() {
    apiModule(dependencies = ApiDependencies.fromEnvironment())
}

fun Application.apiModule(dependencies: ApiDependencies) {
    install(CallLogging)
    configureApiGraphQl(dependencies)
    configureApiErrorHandling()
    configureApiRouting()
    monitor.subscribe(ApplicationStopped) {
        dependencies.close()
    }
}
