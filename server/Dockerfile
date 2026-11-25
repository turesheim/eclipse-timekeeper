FROM eclipse-temurin:21-jre-jammy AS drawio
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip
WORKDIR /download
# Pins cover both the editor distribution and its upstream license.
RUN curl --fail --silent --show-error --location --retry 3 \
      https://github.com/jgraph/drawio/releases/download/v32.6.10/draw.war --output draw.war \
    && echo 'a57b06b3b6a876a351dacfe65d027d9509319cd030e9bacf6ae04a034d66a38f  draw.war' | sha256sum -c - \
    && unzip -q draw.war -d /opt/drawio -x 'META-INF/*' 'WEB-INF/*' \
    && curl --fail --silent --show-error --location --retry 3 \
      https://raw.githubusercontent.com/jgraph/drawio/v32.6.10/LICENSE --output LICENSE \
    && echo '43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1  LICENSE' | sha256sum -c - \
    && mv LICENSE /opt/drawio/LICENSE

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /source
COPY server server
COPY common common
ARG TIMEKEEPER_BUILD_COMMIT=unknown
ARG TIMEKEEPER_BUILD_DIRTY=unknown
ARG DAGSVERK_BUILD_COMMIT=${TIMEKEEPER_BUILD_COMMIT}
ARG DAGSVERK_BUILD_DIRTY=${TIMEKEEPER_BUILD_DIRTY}
RUN mvn -B -ntp -f server/pom.xml package -DskipTests -Dquarkus.profile=postgresql \
    -Dgit.commit.id.full="$DAGSVERK_BUILD_COMMIT" -Dgit.dirty="$DAGSVERK_BUILD_DIRTY"

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 timekeeper && useradd --uid 10001 --gid 10001 --no-create-home timekeeper \
    && install -d -m 0700 -o 10001 -g 10001 /instance
WORKDIR /app
COPY --from=drawio /opt/drawio/ /opt/drawio/
COPY --from=build --chown=10001:10001 /source/server/app/target/quarkus-app/ /app/
COPY --chmod=755 server/deploy/server-entrypoint.sh /app/entrypoint.sh
COPY --chmod=644 server/deploy/environment.sh /app/environment.sh
USER 10001:10001
ENV QUARKUS_PROFILE=postgresql QUARKUS_HTTP_HOST=0.0.0.0 DAGSVERK_LOG_JSON=true
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --connect-timeout 2 --max-time 4 http://127.0.0.1:8080/q/health/ready > /dev/null || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
