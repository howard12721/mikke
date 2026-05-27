import com.google.protobuf.gradle.ProtobufExtension

plugins {
    id("com.google.protobuf")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val protobufVersion = libs.findVersion("protobuf").get().requiredVersion
val grpcVersion = libs.findVersion("grpc").get().requiredVersion
val grpcKotlinVersion = libs.findVersion("grpc-kotlin").get().requiredVersion

extensions.configure<ProtobufExtension> {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}
