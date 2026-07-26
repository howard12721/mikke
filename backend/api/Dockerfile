# syntax=docker/dockerfile:1.25
FROM eclipse-temurin:25.0.3_9-jdk-jammy AS builder

WORKDIR /workspace
ENV GRADLE_USER_HOME=/gradle-cache

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
COPY proto ./proto
COPY api ./api
COPY platform ./platform
COPY events ./events
COPY services ./services

RUN --mount=type=cache,id=mikke-backend-gradle-wrapper,target=/gradle-cache/wrapper,sharing=locked \
    --mount=type=cache,id=mikke-backend-gradle-jdks,target=/gradle-cache/jdks,sharing=locked \
    --mount=type=cache,id=mikke-backend-gradle-caches,target=/gradle-cache/caches \
    ./gradlew :api:installDist --no-daemon

FROM eclipse-temurin:25.0.3_9-jre-jammy AS runtime

WORKDIR /opt/app

COPY --from=builder /workspace/api/build/install/api ./

EXPOSE 8080

ENTRYPOINT ["/opt/app/bin/api"]
