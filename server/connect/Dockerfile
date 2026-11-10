FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /source
COPY server server
COPY common common
ARG TIMEKEEPER_BUILD_COMMIT=unknown
ARG TIMEKEEPER_BUILD_DIRTY=unknown
RUN mvn -B -ntp -f server/pom.xml -pl connect -am package -DskipTests -Dquarkus.profile=postgresql \
    -Dgit.commit.id.full="$TIMEKEEPER_BUILD_COMMIT" -Dgit.dirty="$TIMEKEEPER_BUILD_DIRTY"

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 dagsverk && useradd --uid 10001 --gid 10001 --no-create-home dagsverk
WORKDIR /opt/dagsverk-connect
COPY --from=build --chown=10001:10001 /source/server/connect/target/quarkus-app/ ./
USER 10001:10001
ENV QUARKUS_PROFILE=postgresql QUARKUS_HTTP_HOST=0.0.0.0
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/opt/dagsverk-connect/quarkus-run.jar"]
