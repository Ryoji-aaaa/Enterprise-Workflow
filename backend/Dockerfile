ARG JAVA_VERSION=21
ARG MAVEN_VERSION=3.9.16

FROM maven:${MAVEN_VERSION}-eclipse-temurin-${JAVA_VERSION} AS development
WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src ./src
COPY scripts ./scripts
CMD ["mvn", "--batch-mode", "--no-transfer-progress", "spring-boot:run"]

FROM development AS test
ARG TEST_RUN_ID=manual
RUN echo "Test run: ${TEST_RUN_ID}" \
    && mvn --batch-mode --no-transfer-progress test

FROM development AS build
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/target/workflow-backend-*.jar app.jar
COPY scripts/healthcheck.sh /app/healthcheck.sh
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:${JAVA_VERSION}-jre AS seed-runtime
USER root
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/workflow-backend-*.jar app.jar
COPY scripts/manual-seed.sh /app/manual-seed.sh
COPY scripts/seed-keycloak-users.sh /app/seed-keycloak-users.sh
COPY seed/development-users.tsv /app/development-users.tsv
RUN chmod 0555 /app/manual-seed.sh /app/seed-keycloak-users.sh
USER 10001
ENTRYPOINT ["/app/manual-seed.sh"]
