# syntax=docker/dockerfile:1

# SharkPay identity service (WP-1) — multi-stage build, modelled on
# services/wallet/Dockerfile (same base images, same non-root user, same
# entrypoint conventions).
#
# Build (from the repo root, same context the docker-compose service uses):
#   docker compose build identity
# or standalone:
#   docker build -t sharkpay/identity ./services/identity
#
# Run:
#   docker run -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/sharkpay \
#              -e SPRING_DATASOURCE_USERNAME=sharkpay_identity \
#              -e SPRING_DATASOURCE_PASSWORD=... \
#              -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://keycloak:8080/realms/sharkpay \
#              -p 8082:8081 sharkpay/identity
#
# NOTE (integrator): com.sharkpay:sharkpay-money:1.0.0 lives in the local
# repository of this workspace (packages/java/sharkpay-money) and is NOT on
# Maven Central — CI must publish it to the internal registry or mount a
# pre-populated ~/.m2 before this build stage can resolve dependencies.
# (Same caveat as the wallet/fx/risk Dockerfiles.)

# ---- build stage -----------------------------------------------------------
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
COPY --from=build /build/target/sharkpay-identity-1.0.0.jar /app/app.jar
USER sharkpay:sharkpay
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
