# Multi-stage Dockerfile for WordWaveriseBackend

# Stage 1: Build
FROM gradle:8.11-jdk11 AS builder

WORKDIR /app

# Copy Gradle wrapper
COPY gradle ./gradle
COPY gradlew ./
COPY gradlew.bat ./

# Copy Gradle files
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Copy source code
COPY src ./src

# Make gradlew executable and build the application
RUN chmod +x ./gradlew && ./gradlew buildFatJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:11-jre-alpine

WORKDIR /app

# Install curl for healthchecks
RUN apk add --no-cache curl

# Create non-root user
RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*-all.jar ./app.jar

# Change ownership
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/api/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
