# syntax=docker/dockerfile:1
# Multi-stage: build inside the Gradle wrapper + JDK17, ship a slim JRE runtime.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server ./
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["bin/server"]
