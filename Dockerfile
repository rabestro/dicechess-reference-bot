# syntax=docker/dockerfile:1

# Build stage — JVM bytecode is architecture-independent, so only the runtime stage is multi-arch.
# Pin -noble (Ubuntu 24.04): the unsuffixed tag drifted to 26.04, whose uutils coreutils breaks the launcher.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-noble AS build

ARG SBT_VERSION=1.12.12
ADD https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz /tmp/sbt.tgz
RUN tar -xzf /tmp/sbt.tgz -C /usr/local && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /build

# The engine artifact comes from GitHub Packages, so sbt needs a read:packages token — passed as a
# BuildKit secret so it never lands in an image layer.
ARG GITHUB_ACTOR=rabestro
COPY project/ project/
COPY build.sbt ./
RUN --mount=type=secret,id=github_token \
    GITHUB_TOKEN=$(cat /run/secrets/github_token) sbt update

COPY src/main/ src/main/
RUN --mount=type=secret,id=github_token \
    GITHUB_TOKEN=$(cat /run/secrets/github_token) sbt stage

# Runtime stage. The bot is an outbound client (no listening port), so no EXPOSE/healthcheck.
FROM eclipse-temurin:25-jre-noble

RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/universal/stage /app
USER app

ENV JAVA_OPTS="-Dcats.effect.warnOnNonMainThreadDetected=false --sun-misc-unsafe-memory-access=allow"

ENTRYPOINT ["/app/bin/dicechess-reference-bot"]
