# Image for scripts/gensrc-in-docker.sh: JDK 26 as the boot JDK plus what configure and
# `make gensrc` need on the GitHub Actions ubuntu runner.
FROM eclipse-temurin:26-jdk

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    rm -f /etc/apt/apt.conf.d/docker-clean \
    && apt-get update -qq \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
        git autoconf make zip unzip file gcc g++ \
        libcups2-dev libasound2-dev libfontconfig1-dev \
    && git config --system --add safe.directory '*'
