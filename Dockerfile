# build stage
FROM gradle:9-jdk25 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle installDist --no-daemon

# runtime stage
FROM azul/zulu-openjdk:25-latest
WORKDIR /app
COPY --from=build /home/gradle/src/build/install/daapu/ ./
ENTRYPOINT ["bin/daapu"]
