# syntax=docker/dockerfile:1

# Multi-stage build for the brain:
#   1. build the frontend dist (node)
#   2. copy it into the JVM resources (the `frontend` package served by
#      WebServer.staticWebUi), build the application distribution (zulu JDK)
#   3. runtime: zulu JDK on Ubuntu with a toolbox layer (node/python for the
#      stdio MCP servers, CLI tools) running as root — the brain's bash tool
#      installs more on demand (ephemeral, see stage 3)
# Development behaves differently on purpose: `./gradlew run` has no
# `frontend` resource package (see .gitignore), so the API-only server
# answers 404 for web-UI paths and the UI comes from the vite dev server.

# ---- stage 1: the compiled frontend ----
FROM node:24-alpine AS frontend-build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- stage 2: the application distribution (jar carries the frontend) ----
FROM azul/zulu-openjdk:25-latest AS brain-build
WORKDIR /src
# wrapper + build scripts first: source changes re-run the build without
# re-downloading the Gradle distribution and dependencies (cache mount below)
COPY gradlew gradlew
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src/ src/
# the frontend dist rides as classpath resources, package `frontend`
COPY --from=frontend-build /src/dist/ src/main/resources/frontend/
RUN --mount=type=cache,target=/root/.gradle ./gradlew installDist --no-daemon

# ---- stage 3: runtime (toolbox base) ----
# Root BY DESIGN: the brain's bash tool installs CLI tools at runtime.
# Such installs are per-container ephemeral — anything the deployment
# relies on long-term (stdio MCP servers, CLI tools) belongs in this layer.
FROM azul/zulu-openjdk:25-latest
# Toolbox: node 24 (distro node is 18 — too old for most MCP servers) runs
# the npx-based stdio MCP servers; python3 + uv run the uvx-based ones;
# curl/wget/git/unzip/procps are the common agent-tool needs. gnupg is the
# NodeSource keyring dependency. Full JDK (not JRE) so jcmd/jstack stay
# available — the agent can diagnose its own JVM through the bash tool.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl wget git unzip procps gnupg python3 \
    && curl -fsSL https://deb.nodesource.com/setup_24.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*
# uv/uvx: standalone static binaries from the upstream image, release-pinned
# (an unpinned tag would make builds non-reproducible)
COPY --from=ghcr.io/astral-sh/uv:0.12.9 /uv /uvx /usr/local/bin/
WORKDIR /app
COPY --from=brain-build /src/build/install/daapu/ ./
# config.jsonc is mounted read-only into the workdir at run time
# (loadConfig reads ./config.jsonc) — never bake it into the image; note
# the bash tool's root can read it regardless (the container is the
# isolation boundary)
EXPOSE 8080
CMD ["bin/daapu"]
