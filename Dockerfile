# build stage: frontend
FROM node:26-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# build stage: backend
FROM gradle:9-jdk25 AS backend
WORKDIR /src
# The frontend build writes to ../src/main/resources/static relative to the
# frontend dir, i.e. /src/src/main/resources/static inside the frontend stage.
COPY --from=frontend /src/src/main/resources/static ./src/main/resources/static
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle installDist --no-daemon

# runtime stage
FROM azul/zulu-openjdk:25-jre
WORKDIR /app
COPY --from=backend /src/build/install/daapu/ ./
ENTRYPOINT ["bin/daapu"]
