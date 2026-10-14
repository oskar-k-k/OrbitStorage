# syntax=docker/dockerfile:1
FROM gradle:9.5.1-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S orbit && adduser -S -G orbit orbit && mkdir -p /data/objects && chown -R orbit:orbit /data
WORKDIR /app
COPY --from=build /workspace/build/libs/orbit-storage-0.1.1.jar app.jar
USER orbit
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
