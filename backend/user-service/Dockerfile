FROM gradle:8.10-jdk21 AS build
WORKDIR /home/gradle/project

COPY settings.gradle build.gradle gradle.properties gradlew ./
COPY gradle ./gradle
COPY backend/user-service/build.gradle backend/user-service/

RUN gradle --no-daemon :backend:user-service:dependencies --configuration runtimeClasspath

COPY . .
RUN gradle --no-daemon :backend:user-service:installDist

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl ca-certificates libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 1001 appuser

RUN curl --fail --silent --show-error --location \
    https://truststore.pki.rds.amazonaws.com/eu-central-1/eu-central-1-bundle.pem \
    --output /app/rds-ca-bundle.pem

COPY --from=build --chown=appuser:appuser \
  /home/gradle/project/backend/user-service/build/install/user-service/lib/ /app/lib/

USER appuser
EXPOSE 8081

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD curl --fail --silent http://localhost:8081/health || exit 1

ENTRYPOINT ["java", "-cp", "/app/lib/*", "com.gratilo.user.Application"]
