FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /source
COPY server server
COPY timekeeper-domain timekeeper-domain
COPY timekeeper-service-api timekeeper-service-api
COPY api api
RUN mvn -B -ntp -f server/pom.xml package -DskipTests -Dquarkus.profile=postgresql

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 timekeeper && useradd --uid 10001 --gid 10001 --no-create-home timekeeper
WORKDIR /app
COPY --from=build --chown=10001:10001 /source/server/app/target/quarkus-app/ /app/
COPY --chmod=755 server/deploy/server-entrypoint.sh /app/entrypoint.sh
USER 10001:10001
ENV QUARKUS_PROFILE=postgresql QUARKUS_HTTP_HOST=0.0.0.0
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/q/health/ready > /dev/null || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
