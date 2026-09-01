# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
# NOTE (integrator): com.sharkpay:sharkpay-money:1.0.0 is installed in the
# local repository of this workspace — CI must publish/mount it for this
# stage to resolve dependencies. The Temporal Java SDK (io.temporal:temporal-sdk)
# resolves from Maven Central; only the worker beans need it and they are
# guarded by temporal.enabled (default false) — the image boots without a
# Temporal server until TEMPORAL_ENABLED=true in the deployment environment.
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests clean package

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:25-jre
RUN groupadd -r -g 10001 sharkpay \
 && useradd -r -u 10001 -g 10001 sharkpay
WORKDIR /app
COPY --from=build /build/target/sharkpay-payments-1.0.0.jar /app/app.jar
USER sharkpay:sharkpay
EXPOSE 8085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
