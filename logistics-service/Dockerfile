# syntax=docker/dockerfile:1
# ---- build ----
# Maven as the wrapper pins it (.mvn/wrapper/maven-wrapper.properties; change both together). The image carries
# Maven, so a build never downloads it: the wrapper's one unretried fetch from Maven Central kept failing CI.
FROM maven:3.10.0-eclipse-temurin-21 AS build
WORKDIR /src
# Maven Central answers a burst of parallel CI builds with 429s: retry them, longer than the 3 tries by default, and
# honour Retry-After. Both property prefixes: Resolver 1.9 reads aether.connector.*, 2.x aether.transport.*.
ENV RETRY_RESOLVER_1="-Daether.connector.http.retryHandler.count=8 -Daether.connector.http.retryHandler.interval=10000"
ENV MAVEN_ARGS="$RETRY_RESOLVER_1 -Daether.transport.http.retryHandler.count=8 -Daether.transport.http.retryHandler.interval=10000"
COPY pom.xml .
# One Maven cache per service: compose builds them in parallel, and parallel writers to one cache can collide.
RUN --mount=type=cache,id=m2-logistics-service,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,id=m2-logistics-service,target=/root/.m2 mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
# Layered jar: dependencies change rarely, so they get their own cached image layers.
COPY --from=build /src/target/extracted/dependencies/ ./
COPY --from=build /src/target/extracted/spring-boot-loader/ ./
COPY --from=build /src/target/extracted/snapshot-dependencies/ ./
COPY --from=build /src/target/extracted/application/ ./
USER app
EXPOSE 8082
# JVM sizing comes from the container's memory limit and is set in docker-compose.yml (ADR 0029); JAVA_OPTS replaces
# this default as a whole. exec: the JVM is PID 1 and gets compose's SIGTERM for a graceful shutdown.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
