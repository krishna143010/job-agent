# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon

# Run stage — smaller final image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/job-agent.jar app.jar

# Cloud Run sets PORT env var — our app already reads it
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]