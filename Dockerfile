# syntax=docker/dockerfile:1.7

FROM node:22.14.0-bookworm-slim AS node-runtime

FROM node-runtime AS codex-runtime
ARG CODEX_VERSION=0.144.0
RUN npm install --global --omit=dev "@openai/codex@${CODEX_VERSION}" \
    && npm cache clean --force

FROM clojure:temurin-21-tools-deps-1.12.0.1530-bookworm-slim AS build
COPY --from=node-runtime /usr/local/ /usr/local/
WORKDIR /workspace

COPY deps.edn build.clj shadow-cljs.edn package.json package-lock.json ./
RUN npm ci \
    && clojure -P -M:run \
    && clojure -P -T:build

COPY src ./src
COPY resources ./resources
RUN npx shadow-cljs release app frame \
    && clojure -T:build uber

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=codex-runtime /usr/local/ /usr/local/

RUN groupadd --gid 10001 ppp \
    && useradd --uid 10001 --gid 10001 --home-dir /var/lib/codex \
       --shell /usr/sbin/nologin ppp \
    && install -d -o ppp -g ppp -m 0700 /var/lib/ppp /var/lib/codex \
    && install -d -o ppp -g ppp -m 0755 /opt/ppp

COPY --from=build --chown=ppp:ppp /workspace/target/ppp.jar /opt/ppp/ppp.jar
COPY --chmod=0755 scripts/docker-entrypoint.sh /usr/local/bin/ppp-entrypoint

ENV PPP_ENV=production \
    PPP_PORT=8787 \
    PPP_DATA_DIR=/var/lib/ppp \
    JAVA_TOOL_OPTIONS=-Dorg.sqlite.tmpdir=/var/lib/ppp/.native \
    CODEX_HOME=/var/lib/codex \
    HOME=/var/lib/codex

USER 10001:10001
EXPOSE 8787
VOLUME ["/var/lib/ppp", "/var/lib/codex"]
ENTRYPOINT ["/usr/local/bin/ppp-entrypoint"]
CMD ["java", "-jar", "/opt/ppp/ppp.jar"]
