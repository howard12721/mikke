plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    api(project(":events:event-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}
