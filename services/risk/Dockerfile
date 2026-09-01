# SharkPay risk & compliance service (WP-8) — multi-stage build.
#
# Build:  docker build -t sharkpay/risk .
# Run:    docker run -e RISK_DB_URL=jdbc:postgresql://... -e RISK_DB_USER=... \
#             -e RISK_DB_PASSWORD=... -e KEYCLOAK_ISSUER_URI=... sharkpay/risk
#
# NOTE: the build needs com.sharkpay:sharkpay-money:1.0.0, which lives in the
# monorepo (packages/java/sharkpay-money) and is NOT on Maven Central. CI must
# publish it to the internal registry first, or mount a pre-populated ~/.m2
# (see the commented COPY line) before building from source.
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /src
# COPY ./local-m2 /root/.m2/repository   # optional: pre-warmed repo containing sharkpay-money
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN groupadd -g 65532 nonroot && useradd -u 65532 -g 65532 nonroot
COPY --from=build /src/target/sharkpay-risk-1.0.0.jar /app/app.jar
USER 65532:65532
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
