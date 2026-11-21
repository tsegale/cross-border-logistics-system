# syntax=docker/dockerfile:1
# ---- build ----
# Maven as the wrapper pins it (.mvn/wrapper/maven-wrapper.properties; change both together). The image carries
# Maven, so a build never downloads it: the wrapper's one unretried fetch from Maven Central kept failing CI.
FROM maven:3.10.0-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
# One Maven cache per service: compose builds them in parallel, and parallel writers to one cache can collide.
RUN --mount=type=cache,id=m2-notification-service,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,id=m2-notification-service,target=/root/.m2 mvn -B -q package -DskipTests \
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
EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
