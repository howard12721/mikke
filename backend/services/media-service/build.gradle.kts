plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("mikke.protobuf-conventions")
    application
}

application {
    mainClass = "jp.xhw.mikke.services.media.MediaServiceApplicationKt"
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.file("proto"))
            include("common/v1/*.proto")
            include("media/v1/*.proto")
        }
    }
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":events:media-events"))

    implementation(libs.bundles.grpc.server)
    implementation(libs.bundles.database)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.aws.s3)
    implementation(libs.redis.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("com.h2database:h2:2.3.232")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
