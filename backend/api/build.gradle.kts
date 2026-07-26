plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "jp.xhw.mikke.api.ApiApplicationKt"
}

val graphQlSchemaFile = layout.projectDirectory.file("src/main/graphql/schema.graphqls")

tasks.register<JavaExec>("generateGraphQlSchema") {
    group = "graphql"
    description = "Generates the GraphQL schema SDL."

    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "jp.xhw.mikke.api.graphql.GenerateGraphQlSchemaKt"
    args(graphQlSchemaFile.asFile.absolutePath)
    outputs.file(graphQlSchemaFile)
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":services:identity-service"))
    implementation(project(":services:media-service"))
    implementation(project(":services:post-service"))
    implementation(project(":services:friendship-service"))
    implementation(project(":services:guess-service"))
    implementation(project(":services:notification-service"))

    implementation(libs.bundles.ktor.server)
    implementation(libs.graphql.kotlin.ktor.server)
    implementation(libs.graphql.kotlin.schema.generator)
    implementation(libs.bundles.grpc.client)
    implementation(libs.redis.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
