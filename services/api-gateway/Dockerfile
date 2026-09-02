# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
# NOTE (integrator): like the other Java services, this build resolves its
# dependencies from the workspace's local repository; CI must publish/mount
# them for this stage.
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
COPY --from=build /build/target/sharkpay-api-gateway-1.0.0.jar /app/app.jar
USER sharkpay:sharkpay
EXPOSE 8088
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
