# syntax=docker/dockerfile:1
# The backend image for both ECS services, the API and the worker (D18). They differ only in environment
# variables. Build from backend/: docker build -t photo-space-backend .
# The image contains no secrets and no configuration beyond application*.yml; ECS injects the rest at start.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/main src/main
# Tests run in CI. Skipping them here also keeps test-only code such as dev sign-in out of the image (D8).
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -Dmaven.test.skip=true package \
    && java -Djarmode=tools -jar target/backend-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
# RDS's certificate authorities, so the JDBC driver can verify the database certificate (sslmode=verify-full).
ADD --chmod=444 https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /app/rds-ca.pem
# Dependencies change least often, so they come first and their layers are reused between builds.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
