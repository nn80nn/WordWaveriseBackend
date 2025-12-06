# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

WordWaveriseBackend is a Kotlin-based backend API built with Ktor 3.3.0. The project uses Gradle for build management and runs on the Netty server engine.

- **Main package**: `n.startapp`
- **Main class**: `io.ktor.server.netty.EngineMain`
- **JVM toolchain**: Java 11
- **Server port**: 8080 (configured in `src/main/resources/application.yaml`)

## Build Commands

All commands use Gradle wrapper scripts. On Windows use `gradlew.bat`, on Unix-like systems use `./gradlew`.

| Command | Description |
|---------|-------------|
| `./gradlew run` | Run the development server (starts at http://0.0.0.0:8080) |
| `./gradlew test` | Run all tests |
| `./gradlew build` | Build the project (compiles and runs tests) |
| `./gradlew buildFatJar` | Build an executable JAR with all dependencies |
| `./gradlew buildImage` | Build the Docker image |
| `./gradlew publishImageToLocalRegistry` | Publish Docker image to local registry |
| `./gradlew runDocker` | Run using the local Docker image |

## Architecture

The application follows Ktor's modular architecture pattern:

### Entry Point
- `Application.kt`: Contains `main()` function and the `module()` extension function that registers all feature configurations

### Configuration Functions
The `Application.module()` function calls configuration functions to set up the server (order matters):
1. `configureLogging()` (Logging.kt) - Configures request/response logging with CallLogging plugin
2. `configureSerialization()` (Serialization.kt) - Configures JSON serialization with kotlinx.serialization
3. `configureExceptionHandling()` (ExceptionHandling.kt) - Configures global exception handling with StatusPages
4. `configureHTTP()` (HTTP.kt) - Configures CORS with permissive settings (allows all hosts - needs restriction for production)
5. `configureRouting()` (Routing.kt) - Defines API routes and handlers

### API Response Format
All API responses use the `ApiResponse<T>` wrapper:
```kotlin
{
  "status": "ok" | "error",
  "data": T?,
  "message": String?
}
```

### Current Routes
- `GET /` - Returns "Hello World!" (plain text)
- `GET /api/health` - Health check endpoint, returns `ApiResponse<HealthStatus>` with status and version

Example response:
```json
{
  "status": "ok",
  "data": {
    "status": "ok",
    "version": "1.0.0"
  }
}
```

### Configuration
- **Server config**: `src/main/resources/application.yaml` - Ktor deployment settings (port, modules)
- **Logging**: `src/main/resources/logback.xml` - Logback configuration
- **Build properties**: `gradle.properties` - Kotlin, Ktor, and Logback versions

## Development Patterns

When adding new features:
1. Create configuration functions following the pattern `fun Application.configureXxx()` in separate files
2. Call the configuration function from `Application.module()`
3. For new routes, add them in `Routing.kt` or create domain-specific routing files and call them from `configureRouting()`

## Code Structure

```
src/main/kotlin/n/startapp/
├── Application.kt              # Entry point and module configuration
├── HTTP.kt                     # CORS configuration
├── Routing.kt                  # Route definitions
├── Serialization.kt            # JSON serialization setup
├── Logging.kt                  # Request logging configuration
├── ExceptionHandling.kt        # Global exception handling
├── models/
│   ├── ApiResponse.kt          # Generic API response wrapper
│   └── HealthStatus.kt         # Health check response model
└── exceptions/
    └── ApiException.kt         # Custom exception classes
```

## Exception Handling

The application provides several custom exception classes in `exceptions/ApiException.kt`:
- `ApiException` - Base exception class with configurable status code
- `NotFoundException` (404) - For missing resources
- `BadRequestException` (400) - For invalid requests
- `UnauthorizedException` (401) - For authentication failures
- `ForbiddenException` (403) - For authorization failures

All exceptions are automatically caught and converted to `ApiResponse<Unit>` with appropriate status codes.

## Dependencies

Key dependencies managed in `build.gradle.kts`:
- `kotlin("plugin.serialization")` - Kotlin serialization plugin
- `ktor-server-core-jvm` - Core Ktor server functionality
- `ktor-server-netty` - Netty engine
- `ktor-server-content-negotiation` - Content negotiation plugin
- `ktor-serialization-kotlinx-json` - JSON serialization with kotlinx.serialization
- `ktor-server-status-pages` - Exception handling plugin
- `ktor-server-call-logging` - Request/response logging plugin
- `ktor-server-cors` - CORS support (currently configured with `anyHost()`)
- `ktor-server-config-yaml` - YAML configuration support
- `logback-classic` - Logging framework (SLF4J implementation)
- Test dependencies: `ktor-server-test-host`, `kotlin-test-junit`

## Important Notes

- **CORS Configuration**: Configured to allow requests from `http://localhost:5173` (Vite/React default dev server) and `http://127.0.0.1:5173`. For production, uncomment and configure production domains in HTTP.kt. The configuration includes:
  - Allowed methods: GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD
  - Allowed headers: Authorization, Content-Type, Accept
  - Credentials support enabled for JWT authentication
- The server uses YAML configuration loaded from `application.yaml` to bootstrap the application module
