plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("mikke.protobuf-conventions")
    application
}

application {
    mainClass = "jp.xhw.mikke.services.friendship.FriendshipServiceApplicationKt"
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.file("proto"))
            include("common/v1/*.proto")
            include("friendship/v1/*.proto")
        }
    }
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":events:friendship-events"))

    implementation(libs.bundles.grpc.server)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.bundles.database)
    implementation(libs.redis.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
